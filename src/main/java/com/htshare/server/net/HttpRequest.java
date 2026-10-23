package com.htshare.server.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** A parsed HTTP/1.x request head (request line + headers). HtShare never reads request bodies. */
public final class HttpRequest {

  /** Thrown for requests we refuse to parse; carries the status code to answer with. */
  public static final class BadRequest extends Exception {
    public final int status;

    BadRequest(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private final String method;
  private final String target;
  private final String path;
  private final String query;
  private final boolean http11;
  private final Map<String, String> headers;

  private HttpRequest(
      String method,
      String target,
      String path,
      String query,
      boolean http11,
      Map<String, String> headers) {
    this.method = method;
    this.target = target;
    this.path = path;
    this.query = query;
    this.http11 = http11;
    this.headers = headers;
  }

  /** Parse the bytes before the blank line that ends the head (ISO-8859-1, as HTTP requires). */
  public static HttpRequest parse(String head) throws BadRequest {
    String[] lines = head.split("\r\n");
    String[] parts = lines[0].split(" ");
    if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
      throw new BadRequest(400, "Malformed request line");
    }
    String method = parts[0];
    String target = parts[1];
    if (!target.startsWith("/")) {
      throw new BadRequest(400, "Only origin-form targets are supported");
    }

    Map<String, String> headers = new HashMap<>();
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      int colon = line.indexOf(':');
      if (colon <= 0) {
        throw new BadRequest(400, "Malformed header");
      }
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      headers.merge(name, value, (a, b) -> a + ", " + b);
    }

    int q = target.indexOf('?');
    String rawPath = q < 0 ? target : target.substring(0, q);
    String query = q < 0 ? "" : target.substring(q + 1);
    return new HttpRequest(
        method,
        target,
        decodePath(rawPath),
        query,
        parts[2].equals("HTTP/1.1"),
        Collections.unmodifiableMap(headers));
  }

  /**
   * Percent-decode a URL path as UTF-8. Unlike URLDecoder this leaves '+' alone (in a path '+'
   * is a literal plus), and it rejects bad escapes and NUL bytes.
   */
  static String decodePath(String raw) throws BadRequest {
    ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '%') {
        if (i + 2 >= raw.length()) {
          throw new BadRequest(400, "Bad escape");
        }
        int hi = Character.digit(raw.charAt(i + 1), 16);
        int lo = Character.digit(raw.charAt(i + 2), 16);
        if (hi < 0 || lo < 0 || (hi == 0 && lo == 0)) {
          throw new BadRequest(400, "Bad escape");
        }
        out.write((hi << 4) | lo);
        i += 2;
      } else {
        // the head was read as ISO-8859-1, so each char is exactly one raw byte
        out.write(c & 0xFF);
      }
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  public String method() {
    return method;
  }

  /** The raw request target, e.g. "/Photos/IMG%201.jpg?download=1". */
  public String target() {
    return target;
  }

  /** Decoded path, e.g. "/Photos/IMG 1.jpg". */
  public String path() {
    return path;
  }

  /** Header value by lower-case name, or null. */
  public String header(String lowerName) {
    return headers.get(lowerName);
  }

  /** First value of a query parameter, decoded, or null. */
  public String param(String name) {
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String k = eq < 0 ? pair : pair.substring(0, eq);
      if (k.equals(name)) {
        String v = eq < 0 ? "" : pair.substring(eq + 1).replace('+', ' ');
        try {
          return decodePath(v);
        } catch (BadRequest e) {
          return null;
        }
      }
    }
    return null;
  }

  /** HTTP/1.1 keeps the connection open unless told not to; HTTP/1.0 only if asked. */
  public boolean wantsKeepAlive() {
    String conn = header("connection");
    String c = conn == null ? "" : conn.toLowerCase(Locale.ROOT);
    return http11 ? !c.contains("close") : c.contains("keep-alive");
  }

  /** True if the client says it is sending a body (we never read bodies). */
  public boolean hasBody() {
    String len = header("content-length");
    return header("transfer-encoding") != null || (len != null && !len.equals("0"));
  }
}
