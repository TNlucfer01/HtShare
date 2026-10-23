package com.htshare.server.net;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * One client connection, as an asynchronous byte pipe.
 *
 * <p>Every method returns immediately with a {@link CompletableFuture}; the work happens later on
 * the server's I/O threads, so no thread sits waiting for a slow phone. Callers must not start a
 * second read (or write) before the previous one has completed — the HTTP layer is strictly
 * sequential per connection, which keeps this simple.
 *
 * <p>Two implementations: {@link PlainTransport} (HTTP) and {@link TlsTransport} (HTTPS).
 */
public interface Transport {

  /** Finish any connection setup (the TLS handshake); a no-op for plain HTTP. */
  CompletableFuture<Void> handshake();

  /**
   * Read some bytes into {@code dst}.
   *
   * @return the number of bytes read, or -1 when the client closed the connection
   */
  CompletableFuture<Integer> read(ByteBuffer dst);

  /** Write everything remaining in {@code src}. Completes once it has all been handed to the OS. */
  CompletableFuture<Void> writeFully(ByteBuffer src);

  /** Client address for logs and the transfers panel, e.g. "192.168.1.23". */
  String remoteAddress();

  /** Close the connection. Safe to call more than once. */
  void close();
}
