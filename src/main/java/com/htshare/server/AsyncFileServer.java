package com.htshare.server;

import com.htshare.server.net.HttpConnection;
import com.htshare.server.net.HttpExchange;
import com.htshare.server.net.PlainTransport;
import com.htshare.server.net.TlsTransport;
import com.htshare.server.net.Transport;
import com.htshare.util.SSLCertificateGenerator;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HtShare's file server: asynchronous HTTP/1.1 and HTTPS on Java NIO.2, no extra libraries.
 *
 * <h2>How it works</h2>
 *
 * <ul>
 *   <li>Connections are accepted with {@link AsynchronousServerSocketChannel}. All network I/O
 *       for every client runs on a small fixed pool ({@value #IO_THREADS} threads): a thread only
 *       does work when the OS says a socket is ready, so 2 threads can serve dozens of phones.
 *   <li>Each connection is a chain of async steps ({@link HttpConnection}); files are streamed
 *       chunk by chunk with backpressure ({@link FileResponder}).
 *   <li>Disk reads and the CPU-heavy part of the TLS handshake run on a separate small pool, so
 *       they never stall the network threads.
 *   <li>Slow or idle clients are cut off by read/write timeouts instead of holding threads.
 * </ul>
 *
 * <p>Same public API as the old NanoHTTPD-based server, so the UI didn't need to change.
 */
public class AsyncFileServer {

  private static final Logger logger = LoggerFactory.getLogger(AsyncFileServer.class);

  static final int IO_THREADS = 2;
  static final int WORKER_THREADS = 4;
  static final int MAX_CONNECTIONS = 256;
  /** How far above the preferred port we look for a free one. */
  static final int PORT_SEARCH = 100;
  /** Close a connection that sends nothing for this long (also the keep-alive idle time). */
  static final long READ_TIMEOUT_MS = 30_000;
  /** Close a connection whose client stops accepting data for this long. */
  static final long WRITE_TIMEOUT_MS = 60_000;

  private static final String PAGE_CSP =
      "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
          + "img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; "
          + "frame-ancestors 'none'";

  private final Path root;
  private final Path rootReal;
  private final int preferredPort;
  private final boolean useHttps;
  private final ConnectionMonitor connectionMonitor;
  private final TransferTracker transfers = new TransferTracker();
  private final Helper helper = new Helper();
  private final Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();

  private volatile boolean running;
  private int actualPort;
  private SSLContext sslContext;
  private ExecutorService worker;
  private AsynchronousChannelGroup ioGroup;
  private AsynchronousServerSocketChannel listener;
  private FileResponder files;

  /**
   * @param preferredPort port to try first; the next free one above it is used if it's taken
   * @param timeoutMinutes stop after this many idle minutes (0 = never)
   * @param onTimeout called (on a background thread) when the idle timeout fires
   */
  public AsyncFileServer(
      int preferredPort,
      File rootDirectory,
      int timeoutMinutes,
      Runnable onTimeout,
      boolean useHttps)
      throws IOException {
    if (!rootDirectory.isDirectory()) {
      throw new IOException("Invalid root directory: " + rootDirectory);
    }
    this.root = rootDirectory.toPath().toAbsolutePath().normalize();
    this.rootReal = root.toRealPath();
    this.preferredPort = preferredPort;
    this.useHttps = useHttps;
    this.connectionMonitor = new ConnectionMonitor(timeoutMinutes, onTimeout);
  }

  // ------------------------------------------------------------------ lifecycle

  public synchronized void start() throws IOException {
    if (running) return;
    if (useHttps) {
      sslContext = buildSslContext();
    }
    worker = Executors.newFixedThreadPool(WORKER_THREADS, daemonThreads("htshare-worker"));
    ioGroup =
        AsynchronousChannelGroup.withFixedThreadPool(IO_THREADS, daemonThreads("htshare-io"));
    files = new FileResponder(helper, transfers, connectionMonitor, worker);

    listener = AsynchronousServerSocketChannel.open(ioGroup);
    listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
    actualPort = bindFreePort(listener, preferredPort);

    running = true;
    acceptNext();
    logger.info(
        "{} server listening on port {} ({} I/O threads), serving {}",
        useHttps ? "HTTPS" : "HTTP",
        actualPort,
        IO_THREADS,
        root);
  }

  public synchronized void stop() {
    if (!running) return;
    running = false;
    try {
      listener.close();
    } catch (IOException ignored) {
      // closing anyway
    }
    for (HttpConnection c : new ArrayList<>(connections)) {
      c.close();
    }
    connections.clear();
    try {
      ioGroup.shutdownNow();
    } catch (IOException ignored) {
      // shutting down anyway
    }
    worker.shutdownNow();
    try {
      ioGroup.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    connectionMonitor.shutdown();
    logger.info("Server stopped");
  }

  private static int bindFreePort(AsynchronousServerSocketChannel ch, int preferred)
      throws IOException {
    IOException last = null;
    for (int port = preferred; port <= preferred + PORT_SEARCH && port <= 65535; port++) {
      try {
        ch.bind(new InetSocketAddress(port), 128);
        return port;
      } catch (BindException e) {
        last = e;
      }
    }
    throw new IOException(
        "No free port between " + preferred + " and " + (preferred + PORT_SEARCH), last);
  }

  // ------------------------------------------------------------------ accepting

  private void acceptNext() {
    if (!running) return;
    listener.accept(
        null,
        new CompletionHandler<AsynchronousSocketChannel, Void>() {
          @Override
          public void completed(AsynchronousSocketChannel ch, Void att) {
            acceptNext(); // keep accepting while we set this one up
            onAccepted(ch);
          }

          @Override
          public void failed(Throwable exc, Void att) {
            if (running && !(exc instanceof AsynchronousCloseException)) {
              logger.warn("Accept failed: {}", exc.toString());
              acceptNext();
            }
          }
        });
  }

  private void onAccepted(AsynchronousSocketChannel ch) {
    if (connections.size() >= MAX_CONNECTIONS) {
      logger.warn("Too many connections, refusing one");
      closeQuietly(ch);
      return;
    }
    try {
      ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
      ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
    } catch (IOException e) {
      closeQuietly(ch);
      return;
    }

    Transport transport =
        useHttps
            ? new TlsTransport(ch, newEngine(), worker, READ_TIMEOUT_MS, WRITE_TIMEOUT_MS)
            : new PlainTransport(ch, READ_TIMEOUT_MS, WRITE_TIMEOUT_MS);

    HttpConnection[] self = new HttpConnection[1];
    self[0] =
        new HttpConnection(
            transport,
            this::handle,
            () -> {
              if (connections.remove(self[0])) {
                connectionMonitor.connectionClosed();
              }
            });
    connections.add(self[0]);
    connectionMonitor.connectionOpened();
    self[0].start();
  }

  // ------------------------------------------------------------------ routing

  private CompletableFuture<Void> handle(HttpExchange ex) {
    String method = ex.request().method();
    String path = ex.request().path();

    if (!method.equals("GET") && !method.equals("HEAD")) {
      ex.closeAfterResponse();
      Map<String, String> h = HttpExchange.headers();
      h.put("Allow", "GET, HEAD");
      return ex.send(405, "text/plain; charset=utf-8", "Read-only share".getBytes(), h);
    }

    // The phone page polls this to show "Offline" when you stop sharing.
    // It deliberately does NOT count as activity, or an open page would block auto-stop.
    if (path.equals("/api/ping")) {
      return sendJson(ex, "{\"ok\":true}");
    }

    connectionMonitor.recordRequest(path, method);
    logger.debug("{} {} - {}", method, path, ex.remoteAddress());

    switch (path) {
      case "/api/stats":
        return sendJson(ex, statsJson());
      case "/api/list":
        return listJson(ex);
      case "/favicon.ico":
      case "/apple-touch-icon.png":
        return sendResource(ex, "/image/htshare.png", "image/png");
      default:
        return serveFromRoot(ex, path);
    }
  }

  private CompletableFuture<Void> serveFromRoot(HttpExchange ex, String path) {
    String rel = safeRelativePath(path);
    Path target = rel == null ? null : resolve(rel);
    if (target == null) {
      return ex.sendError(404, "Not found");
    }
    if (Files.isDirectory(target)) {
      // listing a folder touches the disk, so build the page on a worker thread
      return CompletableFuture.supplyAsync(
              () ->
                  helper.generateDirectoryHtml(
                      target.toFile(), rel, root.getFileName() == null ? "/" : root.getFileName().toString()),
              worker)
          .thenCompose(
              html -> {
                Map<String, String> h = HttpExchange.headers();
                h.put("Cache-Control", "no-store");
                h.put("Content-Security-Policy", PAGE_CSP);
                return ex.send(200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), h);
              });
    }
    return files.send(ex, target, rel);
  }

  /**
   * Turn a request path into a clean relative path, or null if it tries anything odd: "..",
   * ".", hidden (dot) files and folders, backslashes, or ':' (Windows drives / data streams).
   */
  static String safeRelativePath(String path) {
    List<String> parts = new ArrayList<>();
    for (String seg : path.split("/")) {
      if (seg.isEmpty()) continue;
      // ':' covers Windows drive letters ("C:") and alternate data streams ("a.txt::$DATA")
      if (seg.startsWith(".")
          || seg.indexOf('\\') >= 0
          || seg.indexOf(':') >= 0
          || seg.indexOf('\0') >= 0) {
        return null;
      }
      parts.add(seg);
    }
    return String.join("/", parts);
  }

  /** Resolve inside the shared folder, following symlinks only if they stay inside it. */
  private Path resolve(String rel) {
    Path p = rel.isEmpty() ? root : root.resolve(rel).normalize();
    if (!p.startsWith(root)) return null;
    try {
      Path real = p.toRealPath();
      return real.startsWith(rootReal) ? real : null;
    } catch (IOException e) {
      return null; // doesn't exist
    }
  }

  // ------------------------------------------------------------------ small endpoints

  private CompletableFuture<Void> sendJson(HttpExchange ex, String json) {
    Map<String, String> h = HttpExchange.headers();
    h.put("Cache-Control", "no-store");
    return ex.send(200, "application/json", json.getBytes(StandardCharsets.UTF_8), h);
  }

  private String statsJson() {
    ConnectionMonitor.ConnectionStats s = connectionMonitor.getStats();
    return String.format(
        "{\"totalRequests\":%d,\"totalDownloads\":%d,\"activeConnections\":%d,"
            + "\"activeTransfers\":%d,\"bytesSent\":%d,\"secondsSinceLastActivity\":%d,"
            + "\"hasActivity\":%b}",
        s.getTotalRequests(),
        transfers.filesSent(),
        s.getActiveConnections(),
        transfers.activeCount(),
        transfers.totalBytesSent(),
        s.getSecondsSinceLastActivity(),
        s.hasHadActivity());
  }

  private CompletableFuture<Void> listJson(HttpExchange ex) {
    String p = ex.request().param("path");
    String rel = safeRelativePath(p == null ? "" : p);
    Path dir = rel == null ? null : resolve(rel);
    if (dir == null || !Files.isDirectory(dir)) {
      return ex.sendError(404, "Directory not found");
    }
    return CompletableFuture.supplyAsync(() -> helper.generateDirectoryJson(dir.toFile()), worker)
        .thenCompose(json -> sendJson(ex, json));
  }

  private CompletableFuture<Void> sendResource(HttpExchange ex, String resource, String type) {
    try (InputStream in = AsyncFileServer.class.getResourceAsStream(resource)) {
      if (in == null) return ex.sendError(404, "Not found");
      Map<String, String> h = HttpExchange.headers();
      h.put("Cache-Control", "max-age=86400");
      return ex.send(200, type, in.readAllBytes(), h);
    } catch (IOException e) {
      return ex.sendError(500, "Error");
    }
  }

  // ------------------------------------------------------------------ TLS

  private static SSLContext buildSslContext() throws IOException {
    try {
      KeyStore keyStore = SSLCertificateGenerator.generateKeyStore();
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, SSLCertificateGenerator.KEYSTORE_PASSWORD.toCharArray());
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), null, null);
      return ctx;
    } catch (Exception e) {
      throw new IOException("SSL setup failed: " + e.getMessage(), e);
    }
  }

  private SSLEngine newEngine() {
    SSLEngine engine = sslContext.createSSLEngine();
    engine.setUseClientMode(false);
    String[] wanted = {"TLSv1.3", "TLSv1.2"};
    engine.setEnabledProtocols(
        Arrays.stream(wanted)
            .filter(p -> Arrays.asList(engine.getSupportedProtocols()).contains(p))
            .toArray(String[]::new));
    return engine;
  }

  // ------------------------------------------------------------------ getters used by the UI

  public int getActualPort() {
    return actualPort;
  }

  public boolean isRunning() {
    return running;
  }

  public ConnectionMonitor getConnectionMonitor() {
    return connectionMonitor;
  }

  public ConnectionMonitor.ConnectionStats getConnectionStats() {
    return connectionMonitor.getStats();
  }

  public TransferTracker getTransfers() {
    return transfers;
  }

  // ------------------------------------------------------------------ helpers

  private static ThreadFactory daemonThreads(String prefix) {
    AtomicInteger n = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }

  private static void closeQuietly(AsynchronousSocketChannel ch) {
    try {
      ch.close();
    } catch (IOException ignored) {
      // gone already
    }
  }
}
