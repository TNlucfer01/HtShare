package com.htshare.server.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One request and its response. Handlers call exactly one of the {@code send*} methods, or
 * {@link #sendHead} followed by their own body writes on {@link #transport()}.
 */
public final class HttpExchange {

  private static final Map<Integer, String> REASONS =
      Map.ofEntries(
          Map.entry(200, "OK"),
          Map.entry(206, "Partial Content"),
          Map.entry(304, "Not Modified"),
          Map.entry(400, "Bad Request"),
          Map.entry(403, "Forbidden"),
          Map.entry(404, "Not Found"),
          Map.entry(405, "Method Not Allowed"),
          Map.entry(408, "Request Timeout"),
          Map.entry(416, "Range Not Satisfiable"),
          Map.entry(431, "Request Header Fields Too Large"),
          Map.entry(500, "Internal Server Error"),
          Map.entry(503, "Service Unavailable"));

  public static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

  private final HttpRequest request;
  private final Transport transport;
  private boolean keepAlive;

  HttpExchange(HttpRequest request, Transport transport, boolean keepAlive) {
    this.request = request;
    this.transport = transport;
    this.keepAlive = keepAlive;
  }

  public HttpRequest request() {
    return request;
  }

  public Transport transport() {
    return transport;
  }

  public String remoteAddress() {
    return transport.remoteAddress();
  }

  public boolean isHead() {
    return request != null && "HEAD".equals(request.method());
  }

  /** Ask for the connection to be closed after this response. */
  public void closeAfterResponse() {
    keepAlive = false;
  }

  boolean keepAlive() {
    return keepAlive;
  }

  /** New header map pre-filled with the headers every response gets. */
  public static Map<String, String> headers() {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Server", "HtShare");
    h.put("Date", HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC)));
    h.put("X-Content-Type-Options", "nosniff");
    h.put("Referrer-Policy", "no-referrer");
    return h;
  }

  /** Write the status line and headers (adds Connection). The body, if any, is the caller's. */
  public CompletableFuture<Void> sendHead(int status, Map<String, String> headers) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("HTTP/1.1 ")
        .append(status)
        .append(' ')
        .append(REASONS.getOrDefault(status, "Status"))
        .append("\r\n");
    headers.put("Connection", keepAlive ? "keep-alive" : "close");
    for (Map.Entry<String, String> e : headers.entrySet()) {
      sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
    }
    sb.append("\r\n");
    // header values are ASCII (filenames go through RFC 5987 encoding), so this is lossless
    return transport.writeFully(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.ISO_8859_1)));
  }

  /** A complete small response: head + body in one write. */
  public CompletableFuture<Void> send(int status, String contentType, byte[] body, Map<String, String> headers) {
    headers.put("Content-Type", contentType);
    headers.put("Content-Length", String.valueOf(body.length));
    if (isHead() || status == 304) {
      return sendHead(status, headers);
    }
    return sendHead(status, headers).thenCompose(v -> transport.writeFully(ByteBuffer.wrap(body)));
  }

  public CompletableFuture<Void> sendText(int status, String contentType, String body) {
    return send(status, contentType, body.getBytes(StandardCharsets.UTF_8), headers());
  }

  /** Small plain error page. */
  public CompletableFuture<Void> sendError(int status, String message) {
    Map<String, String> h = headers();
    h.put("Cache-Control", "no-store");
    return send(status, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8), h);
  }
}
