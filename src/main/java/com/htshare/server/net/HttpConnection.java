package com.htshare.server.net;

import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves HTTP/1.1 requests on one connection, one after another (keep-alive).
 *
 * <p>The whole life of a connection is a chain of async steps — handshake → read head → handle →
 * read next head … — so it never owns a thread while waiting for the client.
 */
public final class HttpConnection {

  private static final Logger logger = LoggerFactory.getLogger(HttpConnection.class);

  /** Largest request head we accept (request line + headers). */
  static final int MAX_HEAD_BYTES = 16 * 1024;
  /** Requests served on one connection before we ask the client to reconnect. */
  static final int MAX_REQUESTS = 1000;

  /** What the server does with each request. */
  @FunctionalInterface
  public interface Handler {
    /** Respond to the exchange; the returned future completes when the response is fully sent. */
    CompletableFuture<Void> handle(HttpExchange exchange);
  }

  private final Transport transport;
  private final Handler handler;
  private final Runnable onClose;
  // request head bytes, kept in fill mode; may hold the start of the next pipelined request
  private final ByteBuffer head = ByteBuffer.allocate(MAX_HEAD_BYTES);
  private int served;
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();

  public HttpConnection(Transport transport, Handler handler, Runnable onClose) {
    this.transport = transport;
    this.handler = handler;
    this.onClose = onClose;
  }

  public Transport transport() {
    return transport;
  }

  /** Begin serving. Returns immediately. */
  public void start() {
    transport
        .handshake()
        .whenComplete(
            (v, err) -> {
              if (err != null) {
                logger.debug("Handshake with {} failed: {}", transport.remoteAddress(), String.valueOf(root(err)));
                close();
              } else {
                nextRequest();
              }
            });
  }

  private void nextRequest() {
    int end = findHeadEnd();
    if (end >= 0) {
      handleHead(end);
      return;
    }
    if (!head.hasRemaining()) {
      reject(431, "Request headers too large");
      return;
    }
    transport
        .read(head)
        .whenComplete(
            (n, err) -> {
              if (err != null) {
                if (!(root(err) instanceof InterruptedByTimeoutException)) {
                  logger.debug("Read from {} failed: {}", transport.remoteAddress(), String.valueOf(root(err)));
                }
                close(); // idle keep-alive timeout or client went away
              } else if (n < 0) {
                close();
              } else {
                nextRequest();
              }
            });
  }

  private void handleHead(int end) {
    String text =
        new String(head.array(), 0, end, StandardCharsets.ISO_8859_1); // excludes the blank line
    // drop the head (and its \r\n\r\n) from the buffer, keep anything after it
    head.flip();
    head.position(end + 4);
    head.compact();

    HttpRequest req;
    try {
      req = HttpRequest.parse(text);
    } catch (HttpRequest.BadRequest e) {
      reject(e.status, e.getMessage());
      return;
    }

    served++;
    boolean keepAlive = req.wantsKeepAlive() && served < MAX_REQUESTS && !req.hasBody();
    HttpExchange ex = new HttpExchange(req, transport, keepAlive);

    CompletableFuture<Void> response;
    try {
      response = handler.handle(ex);
    } catch (RuntimeException e) {
      response = CompletableFuture.failedFuture(e);
    }
    response.whenComplete(
        (v, err) -> {
          if (err != null) {
            logger.debug("Response to {} failed: {}", transport.remoteAddress(), String.valueOf(root(err)));
            close();
          } else if (ex.keepAlive()) {
            nextRequest();
          } else {
            close();
          }
        });
  }

  private void reject(int status, String message) {
    HttpExchange ex = new HttpExchange(null, transport, false);
    ex.sendError(status, message).whenComplete((v, err) -> close());
  }

  /** Index of the "\r\n\r\n" ending the head, or -1. */
  private int findHeadEnd() {
    byte[] a = head.array();
    for (int i = 0; i + 3 < head.position(); i++) {
      if (a[i] == '\r' && a[i + 1] == '\n' && a[i + 2] == '\r' && a[i + 3] == '\n') {
        return i;
      }
    }
    return -1;
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    transport.close();
    if (onClose != null) {
      onClose.run();
    }
  }

  static Throwable root(Throwable t) {
    while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }
}
