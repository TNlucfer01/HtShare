package com.htshare.server;

// it uses th einbuilt httpserver not the nanohttpd we should changeone of the these two
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP File Server using Java's built-in HttpServer Serves files from a specified directory with
 * dynamic port assignment
 */
public class HttpFileServer {
  private static final Logger logger = LoggerFactory.getLogger(HttpFileServer.class);
  private static final int THREAD_POOL_SIZE = 10;

  private HttpServer server;
  private File rootDirectory;
  private int actualPort;
  Helper helper = new Helper();
  private final ConnectionMonitor connectionMonitor; // don't know why we need this

  /**
   * Create a new HTTP file server
   *
   * @param preferredPort Preferred port (will find available if taken)
   * @param rootDirectory Directory to serve files from
   */
  public HttpFileServer(int preferredPort, File rootDirectory) throws IOException {
    this(preferredPort, rootDirectory, 0, null);
  }

  /**
   * Create a new HTTP file server with timeout
   *
   * @param preferredPort Preferred port
   * @param rootDirectory Directory to serve files from
   * @param timeoutMinutes Inactivity timeout in minutes (0 = disabled)
   * @param onTimeout Callback when timeout occurs
   */
  public HttpFileServer(
      int preferredPort, File rootDirectory, int timeoutMinutes, Runnable onTimeout)
      throws IOException {
    if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
      throw new IOException("Invalid root directory: " + rootDirectory);
    }

    this.rootDirectory = rootDirectory;
    this.actualPort = helper.findAvailablePort(preferredPort);

    // Initialize connection monitor
    this.connectionMonitor = new ConnectionMonitor(timeoutMinutes, onTimeout);

    // Create HTTP server
    this.server = HttpServer.create(new InetSocketAddress(actualPort), 0);

    // Set up request handlers
    this.server.createContext("/", new FileHandler());
    this.server.createContext("/api/list", new ApiListHandler());
    this.server.createContext("/style.css", new CssHandler());
    this.server.createContext("/api/stats", new StatsHandler());
    // Use thread pool for handling requests
    this.server.setExecutor(Executors.newFixedThreadPool(THREAD_POOL_SIZE));

    logger.info(
        "HTTP File Server initialized on port {} with root: {}",
        actualPort,
        rootDirectory.getAbsolutePath());
  }

  /** Handler for serving server statistics */
  private class StatsHandler implements HttpHandler {

    ConnectionMonitor.ConnectionStats stats = connectionMonitor.getStats();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
          sendJsonResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
          return;
        }

        if (stats == null) {
          sendJsonResponse(exchange, 500, "{\"error\": \"Statistics not available\"}");
          return;
        }

        String json =
            String.format(
                "{\"totalRequests\":%d,\"totalDownloads\":%d,\"activeConnections\":%d,"
                    + "\"secondsSinceLastActivity\":%d,\"hasActivity\":%b}",
                stats.getTotalRequests(),
                stats.getTotalDownloads(),
                stats.getActiveConnections(),
                stats.getSecondsSinceLastActivity(),
                stats.hasHadActivity());

        sendJsonResponse(exchange, 200, json);

      } catch (Exception e) {
        logger.error("Error in stats handler", e);
        sendJsonResponse(
            exchange, 500, "{\"error\": \"" + helper.escapeJson(e.getMessage()) + "\"}");
      }
    }
  }

  /** Start the server */
  public void start() {
    server.start();
    logger.info("HTTP File Server started on port {}", actualPort);
  }

  /** Stop the server */
  public void stop() {
    if (server != null) {
      server.stop(0);
      logger.info("HTTP File Server stopped");
    }
    if (connectionMonitor != null) {
      connectionMonitor.shutdown();
    }
  }

  /** Get the actual port being used */
  public int getActualPort() {
    return actualPort;
  }

  /** Get connection statistics */
  public ConnectionMonitor.ConnectionStats getConnectionStats() {
    return connectionMonitor != null ? connectionMonitor.getStats() : null;
  }

  /** Get connection monitor */
  public ConnectionMonitor getConnectionMonitor() {
    return connectionMonitor;
  }

  private static final class CssHandler implements HttpHandler {

    private static final String CSS_RESOURCE = "/css/client-css.css";

    private static final byte[] CSS_BYTES;

    static {
      try (InputStream is = CssHandler.class.getResourceAsStream(CSS_RESOURCE)) {

        if (is == null) {
          throw new IllegalStateException("CSS resource not found: " + CSS_RESOURCE);
        }

        CSS_BYTES = is.readAllBytes();
      } catch (IOException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {

      if (!"GET".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }

      Headers headers = exchange.getResponseHeaders();
      headers.set("Content-Type", "text/css; charset=utf-8");
      headers.set("Cache-Control", "public, max-age=31536000, immutable");

      exchange.sendResponseHeaders(200, CSS_BYTES.length);

      try (OutputStream os = exchange.getResponseBody()) {
        os.write(CSS_BYTES);
      }
    }
  }

  /** Main file serving handler */
  // why is it differnt from the https approach
  private class FileHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();

      // Record request in monitor
      connectionMonitor.recordRequest(path, method);
      connectionMonitor.connectionOpened();

      logger.info("{} {}", method, path);

      try {
        // Only handle GET requests
        if (!"GET".equalsIgnoreCase(method)) {
          sendResponse(exchange, 405, "Method Not Allowed");
        }

        // Decode and sanitize path what does sanitize even means
        path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (path.startsWith("/")) {
          path = path.substring(1);
        }

        // Resolve file
        File file = new File(rootDirectory, path);

        // Security: Prevent directory traversal
        if (!file.getCanonicalPath().startsWith(rootDirectory.getCanonicalPath())) {
          logger.warn("Directory traversal attempt: {}", path);
          sendResponse(exchange, 403, "Access Denied");
          return;
        }

        // Check if file exists
        if (!file.exists()) {
          sendResponse(exchange, 404, "File Not Found");
          return;
        }

        // Serve directory listing or file
        if (file.isDirectory()) {
          serveDirectoryListing(exchange, file, path);
        } else {
          serveFile(exchange, file);
        }

      } catch (Exception e) {
        logger.error("Error handling request", e);
        sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
      } finally {
        connectionMonitor.connectionClosed();
      }
    }

    private void serveFile(HttpExchange exchange, File file) throws IOException {
      // Determine MIME type
      String mimeType = helper.getMimeType(file.getName());

      // Set headers
      exchange.getResponseHeaders().set("Content-Type", mimeType);
      exchange
          .getResponseHeaders()
          .set("Content-Disposition", "inline; filename=\"" + file.getName() + "\"");
      exchange.getResponseHeaders().set("Accept-Ranges", "bytes");

      // Send file
      byte[] fileBytes = Files.readAllBytes(file.toPath());
      exchange.sendResponseHeaders(200, fileBytes.length);

      try (OutputStream os = exchange.getResponseBody()) {
        os.write(fileBytes);
      }

      // Record download
      connectionMonitor.recordDownload(file.getName(), fileBytes.length);

      logger.info("Served file: {} ({} bytes)", file.getName(), fileBytes.length);
    }

    private void serveDirectoryListing(HttpExchange exchange, File directory, String relativePath)
        throws IOException {

      String html = helper.generateDirectoryHtml(directory, relativePath);
      byte[] response = html.getBytes(StandardCharsets.UTF_8);

      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
      exchange.sendResponseHeaders(200, response.length);

      try (OutputStream os = exchange.getResponseBody()) {
        os.write(response);
      }

      logger.info("Served directory listing: {}", directory.getName());
    }
  }

  /** API handler for listing files (returns JSON) */
  private class ApiListHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        String query = exchange.getRequestURI().getQuery();
        String path = "";

        if (query != null && query.startsWith("path=")) {
          path = URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8);
        }

        File directory = new File(rootDirectory, path);

        // Security check
        if (!directory.getCanonicalPath().startsWith(rootDirectory.getCanonicalPath())) {
          sendJsonResponse(exchange, 403, "{\"error\": \"Access denied\"}");
          return;
        }

        if (!directory.exists() || !directory.isDirectory()) {
          sendJsonResponse(exchange, 404, "{\"error\": \"Directory not found\"}");
          return;
        }

        String json = helper.generateDirectoryJson(directory);
        sendJsonResponse(exchange, 200, json);

      } catch (Exception e) {
        logger.error("Error in API handler", e);
        sendJsonResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
      }
    }
  }

  /** Generate HTML for directory listing */
  // Helper methods
  private void sendResponse(HttpExchange exchange, int statusCode, String message)
      throws IOException {
    byte[] response = message.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(statusCode, response.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response);
    }
  }

  private void sendJsonResponse(HttpExchange exchange, int statusCode, String json)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    byte[] response = json.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(statusCode, response.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response);
    }
  }
}
