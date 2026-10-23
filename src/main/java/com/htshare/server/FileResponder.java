package com.htshare.server;

import com.htshare.server.net.HttpExchange;
import com.htshare.server.net.Transport;
import java.io.EOFException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Sends one file, asynchronously.
 *
 * <p>The transfer is a loop of two async steps: read a chunk from disk ({@link
 * AsynchronousFileChannel}) → write it to the client ({@link Transport}) → read the next chunk.
 * The next disk read only starts after the client has taken the previous chunk, so a slow phone
 * simply slows the loop down (backpressure): memory per transfer stays at one chunk, and no thread
 * is ever parked waiting for the network.
 *
 * <p>Supports byte ranges (HTTP 206), so browsers can resume interrupted downloads and video
 * players can seek; and ETag / Last-Modified, so unchanged files aren't sent twice.
 */
final class FileResponder {

  /** Bytes per disk read / network write step. */
  static final int CHUNK = 256 * 1024;

  private final Helper helper;
  private final TransferTracker tracker;
  private final ConnectionMonitor monitor;
  private final ExecutorService diskExecutor;

  FileResponder(
      Helper helper,
      TransferTracker tracker,
      ConnectionMonitor monitor,
      ExecutorService diskExecutor) {
    this.helper = helper;
    this.tracker = tracker;
    this.monitor = monitor;
    this.diskExecutor = diskExecutor;
  }

  CompletableFuture<Void> send(HttpExchange ex, Path file, String relativePath) {
    BasicFileAttributes attrs;
    try {
      attrs = Files.readAttributes(file, BasicFileAttributes.class);
    } catch (IOException e) {
      return ex.sendError(404, "File not found");
    }
    long size = attrs.size();
    long mtime = attrs.lastModifiedTime().toMillis();
    String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(mtime) + "\"";
    String lastModified =
        HttpExchange.HTTP_DATE.format(Instant.ofEpochMilli(mtime).atZone(ZoneOffset.UTC));
    String name = file.getFileName().toString();

    Map<String, String> h = HttpExchange.headers();
    h.put("ETag", etag);
    h.put("Last-Modified", lastModified);
    h.put("Accept-Ranges", "bytes");
    h.put("Cache-Control", "no-cache"); // may cache, but must check the ETag first

    // Unchanged since the browser's copy?
    String ifNoneMatch = ex.request().header("if-none-match");
    if (ifNoneMatch != null && (ifNoneMatch.equals("*") || ifNoneMatch.contains(etag))) {
      return ex.sendHead(304, h);
    }

    // Byte range? Only honour it if the file is the version the client started with.
    long start = 0;
    long end = size - 1;
    int status = 200;
    String range = ex.request().header("range");
    String ifRange = ex.request().header("if-range");
    boolean rangeApplies = ifRange == null || ifRange.equals(etag) || ifRange.equals(lastModified);
    if (range != null && rangeApplies && size > 0) {
      long[] r = parseRange(range, size);
      if (r != null && r.length == 0) {
        h.put("Content-Range", "bytes */" + size);
        return ex.send(416, "text/plain; charset=utf-8", new byte[0], h);
      }
      if (r != null) {
        start = r[0];
        end = r[1];
        status = 206;
        h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
      }
    }
    long length = size == 0 ? 0 : end - start + 1;

    String mime = helper.getMimeType(name);
    h.put("Content-Type", mime);
    h.put("Content-Length", String.valueOf(length));
    boolean forceDownload = ex.request().param("download") != null;
    h.put("Content-Disposition", contentDisposition(forceDownload ? "attachment" : "inline", name));
    if (mime.startsWith("text/html") || mime.startsWith("image/svg") || mime.contains("xml")) {
      // a shared .html file must not run scripts on this server's origin
      h.put("Content-Security-Policy", "sandbox");
    }

    if (ex.isHead() || length == 0) {
      return ex.sendHead(status, h);
    }

    AsynchronousFileChannel channel;
    try {
      // the executor runs the actual disk reads (Linux has no true async file I/O for Java),
      // keeping them off the network threads
      channel =
          AsynchronousFileChannel.open(file, Set.of(StandardOpenOption.READ), diskExecutor);
    } catch (IOException e) {
      return ex.sendError(403, "Cannot read file");
    }

    String device = DeviceNames.label(ex.request().header("user-agent"), ex.remoteAddress());
    TransferTracker.Handle transfer = tracker.begin(relativePath, name, device, size, start);
    long from = start;

    return ex.sendHead(status, h)
        .thenCompose(v -> pump(channel, ex.transport(), from, length, transfer))
        .whenComplete(
            (v, err) -> {
              transfer.end();
              try {
                channel.close();
              } catch (IOException ignored) {
                // nothing to do
              }
            });
  }

  /** The read-chunk → write-chunk loop. */
  private CompletableFuture<Void> pump(
      AsynchronousFileChannel file,
      Transport out,
      long position,
      long remaining,
      TransferTracker.Handle transfer) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    ByteBuffer buf = ByteBuffer.allocateDirect((int) Math.min(CHUNK, Math.max(remaining, 1)));
    step(file, out, buf, position, remaining, transfer, done);
    return done;
  }

  private void step(
      AsynchronousFileChannel file,
      Transport out,
      ByteBuffer buf,
      long position,
      long remaining,
      TransferTracker.Handle transfer,
      CompletableFuture<Void> done) {
    if (remaining == 0) {
      done.complete(null);
      return;
    }
    buf.clear();
    buf.limit((int) Math.min(buf.capacity(), remaining));
    file.read(
        buf,
        position,
        null,
        new CompletionHandler<Integer, Void>() {
          @Override
          public void completed(Integer n, Void att) {
            if (n <= 0) {
              // file got shorter while we were sending it; we promised Content-Length, so the
              // only honest thing left is to drop the connection
              done.completeExceptionally(new EOFException("file truncated during transfer"));
              return;
            }
            buf.flip();
            out.writeFully(buf)
                .whenComplete(
                    (v, err) -> {
                      if (err != null) {
                        done.completeExceptionally(err); // client went away
                        return;
                      }
                      transfer.progress(n);
                      monitor.touch();
                      step(file, out, buf, position + n, remaining - n, transfer, done);
                    });
          }

          @Override
          public void failed(Throwable exc, Void att) {
            done.completeExceptionally(exc);
          }
        });
  }

  /**
   * Parse a single "bytes=" range.
   *
   * @return {start, end} inclusive; an empty array if it can't be satisfied (→ 416); or null to
   *     ignore the header and send the whole file (multiple ranges, malformed)
   */
  static long[] parseRange(String header, long size) {
    if (!header.startsWith("bytes=")) return null;
    String spec = header.substring(6).trim();
    if (spec.contains(",")) return null;
    int dash = spec.indexOf('-');
    if (dash < 0) return null;
    String a = spec.substring(0, dash).trim();
    String b = spec.substring(dash + 1).trim();
    try {
      if (a.isEmpty()) { // "bytes=-500": the last 500 bytes
        long n = Long.parseLong(b);
        if (n <= 0) return new long[0];
        return new long[] {Math.max(0, size - n), size - 1};
      }
      long start = Long.parseLong(a);
      long end = b.isEmpty() ? size - 1 : Math.min(Long.parseLong(b), size - 1);
      if (start < 0 || (!b.isEmpty() && Long.parseLong(b) < start)) return null;
      if (start >= size) return new long[0];
      return new long[] {start, end};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** RFC 6266 header with an ASCII fallback name plus the exact UTF-8 name. */
  static String contentDisposition(String type, String name) {
    StringBuilder ascii = new StringBuilder();
    for (char c : name.toCharArray()) {
      ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
    }
    String utf8 = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    return type + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8;
  }
}
