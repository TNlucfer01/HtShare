package com.htshare.server.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plain (unencrypted) transport on top of an {@link AsynchronousSocketChannel}.
 *
 * <p>{@code AsynchronousSocketChannel} is Java's NIO.2 async socket: {@code read}/{@code write}
 * return at once and call a {@link CompletionHandler} when the OS reports the operation is done.
 * Here each handler is turned into a {@link CompletableFuture} so callers can chain steps with
 * {@code thenCompose}.
 *
 * <p>Also the base class for {@link TlsTransport}, which uses {@link #readRaw}/{@link
 * #writeRawFully} to move encrypted bytes.
 */
public class PlainTransport implements Transport {

  protected final AsynchronousSocketChannel channel;
  private final long readTimeoutMs;
  private final long writeTimeoutMs;
  private final String remote;
  private final AtomicBoolean closed = new AtomicBoolean();

  public PlainTransport(AsynchronousSocketChannel channel, long readTimeoutMs, long writeTimeoutMs) {
    this.channel = channel;
    this.readTimeoutMs = readTimeoutMs;
    this.writeTimeoutMs = writeTimeoutMs;
    this.remote = describe(channel);
  }

  @Override
  public CompletableFuture<Void> handshake() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Integer> read(ByteBuffer dst) {
    return readRaw(dst);
  }

  @Override
  public CompletableFuture<Void> writeFully(ByteBuffer src) {
    return writeRawFully(src);
  }

  /**
   * One socket read. Times out (fails with InterruptedByTimeoutException) if the client sends
   * nothing for {@code readTimeoutMs} — this is what frees idle keep-alive connections and
   * stops "slow loris" clients from holding a connection open forever.
   */
  protected final CompletableFuture<Integer> readRaw(ByteBuffer dst) {
    CompletableFuture<Integer> result = new CompletableFuture<>();
    try {
      channel.read(dst, readTimeoutMs, TimeUnit.MILLISECONDS, result, IntHandler.INSTANCE);
    } catch (RuntimeException e) {
      result.completeExceptionally(e);
    }
    return result;
  }

  /** Socket writes until {@code src} is empty (the OS may accept only part of it per write). */
  protected final CompletableFuture<Void> writeRawFully(ByteBuffer src) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    writeLoop(src, done);
    return done;
  }

  private void writeLoop(ByteBuffer src, CompletableFuture<Void> done) {
    if (!src.hasRemaining()) {
      done.complete(null);
      return;
    }
    try {
      channel.write(
          src,
          writeTimeoutMs,
          TimeUnit.MILLISECONDS,
          null,
          new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer written, Void att) {
              writeLoop(src, done);
            }

            @Override
            public void failed(Throwable exc, Void att) {
              done.completeExceptionally(exc);
            }
          });
    } catch (RuntimeException e) {
      done.completeExceptionally(e);
    }
  }

  @Override
  public String remoteAddress() {
    return remote;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        channel.close();
      } catch (IOException ignored) {
        // already gone
      }
    }
  }

  protected boolean isClosed() {
    return closed.get();
  }

  private static String describe(AsynchronousSocketChannel ch) {
    try {
      SocketAddress a = ch.getRemoteAddress();
      if (a instanceof InetSocketAddress isa && isa.getAddress() != null) {
        return isa.getAddress().getHostAddress();
      }
      return String.valueOf(a);
    } catch (IOException e) {
      return "unknown";
    }
  }

  /** Shared handler: completes the future passed as the attachment. */
  private enum IntHandler implements CompletionHandler<Integer, CompletableFuture<Integer>> {
    INSTANCE;

    @Override
    public void completed(Integer n, CompletableFuture<Integer> f) {
      f.complete(n);
    }

    @Override
    public void failed(Throwable exc, CompletableFuture<Integer> f) {
      f.completeExceptionally(exc);
    }
  }
}
