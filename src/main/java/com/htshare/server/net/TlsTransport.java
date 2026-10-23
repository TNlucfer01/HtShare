package com.htshare.server.net;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

/**
 * HTTPS transport: TLS on top of the async socket.
 *
 * <p>Java's {@link SSLEngine} does the cryptography but no I/O. We feed it encrypted bytes from
 * the socket ({@code unwrap} → plain bytes) and ask it to encrypt our output ({@code wrap} →
 * bytes for the socket). Because all socket I/O stays asynchronous, an HTTPS download never
 * blocks a thread either.
 *
 * <p>Buffer conventions (the usual source of SSLEngine bugs, so spelled out):
 *
 * <ul>
 *   <li>{@code netIn} — encrypted bytes received, kept in <b>fill</b> mode between calls
 *       (position = end of data).
 *   <li>{@code appIn} — decrypted bytes not yet handed to the caller, kept in <b>drain</b> mode
 *       (data is position..limit).
 *   <li>{@code netOut} — scratch space for encrypted output.
 * </ul>
 */
public final class TlsTransport extends PlainTransport {

  private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
  private static final int UNDERFLOW = -2;
  private static final int CLOSED = -1;

  private final SSLEngine engine;
  private final Executor taskExecutor;
  private final AtomicBoolean closing = new AtomicBoolean();

  private ByteBuffer netIn;
  private ByteBuffer appIn;
  private ByteBuffer netOut;

  /**
   * @param taskExecutor where the engine's CPU-heavy handshake tasks (key exchange) run, so they
   *     don't stall the I/O threads
   */
  public TlsTransport(
      AsynchronousSocketChannel channel,
      SSLEngine engine,
      Executor taskExecutor,
      long readTimeoutMs,
      long writeTimeoutMs) {
    super(channel, readTimeoutMs, writeTimeoutMs);
    this.engine = engine;
    this.taskExecutor = taskExecutor;
    SSLSession session = engine.getSession();
    this.netIn = ByteBuffer.allocate(session.getPacketBufferSize());
    this.appIn = ByteBuffer.allocate(session.getApplicationBufferSize()).flip();
    // room for a few TLS records per socket write: fewer syscalls on big downloads
    this.netOut = ByteBuffer.allocateDirect(session.getPacketBufferSize() * 4);
  }

  // ---------------------------------------------------------------- handshake

  @Override
  public CompletableFuture<Void> handshake() {
    try {
      engine.beginHandshake();
    } catch (SSLException e) {
      return CompletableFuture.failedFuture(e);
    }
    return drive();
  }

  /** Advance the handshake until the engine says it is done. */
  private CompletableFuture<Void> drive() {
    try {
      while (true) {
        HandshakeStatus hs = engine.getHandshakeStatus();
        switch (hs) {
          case NEED_TASK:
            return CompletableFuture.runAsync(this::runDelegatedTasks, taskExecutor)
                .thenCompose(v -> drive());
          case NEED_WRAP:
            return wrapAndSend(EMPTY).thenCompose(v -> drive());
          case NEED_UNWRAP:
          case NEED_UNWRAP_AGAIN:
            int r = unwrapStep();
            if (r == CLOSED) {
              return CompletableFuture.failedFuture(new EOFException("closed during handshake"));
            }
            if (r == UNDERFLOW) {
              return fillNetIn()
                  .thenCompose(
                      n ->
                          n < 0
                              ? CompletableFuture.failedFuture(
                                  new EOFException("closed during handshake"))
                              : drive());
            }
            continue; // made progress, look at the new status
          default: // FINISHED, NOT_HANDSHAKING
            return CompletableFuture.completedFuture(null);
        }
      }
    } catch (SSLException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private void runDelegatedTasks() {
    Runnable task;
    while ((task = engine.getDelegatedTask()) != null) {
      task.run();
    }
  }

  private boolean handshaking() {
    HandshakeStatus hs = engine.getHandshakeStatus();
    return hs != HandshakeStatus.NOT_HANDSHAKING && hs != HandshakeStatus.FINISHED;
  }

  // ---------------------------------------------------------------- read

  @Override
  public CompletableFuture<Integer> read(ByteBuffer dst) {
    try {
      while (true) {
        if (appIn.hasRemaining()) {
          int n = Math.min(appIn.remaining(), dst.remaining());
          ByteBuffer slice = appIn.slice(appIn.position(), n);
          dst.put(slice);
          appIn.position(appIn.position() + n);
          return CompletableFuture.completedFuture(n);
        }
        int r = unwrapStep();
        if (r == CLOSED) {
          return CompletableFuture.completedFuture(-1);
        }
        if (r == UNDERFLOW) {
          return fillNetIn()
              .thenCompose(n -> n < 0 ? CompletableFuture.completedFuture(-1) : read(dst));
        }
        if (handshaking()) {
          return drive().thenCompose(v -> read(dst));
        }
        // r >= 0: decrypted something (or consumed a non-data record); loop
      }
    } catch (SSLException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Decrypt one record from netIn into appIn.
   *
   * @return bytes produced, {@link #UNDERFLOW} if more socket data is needed, or {@link #CLOSED}
   */
  private int unwrapStep() throws SSLException {
    netIn.flip();
    appIn.compact();
    SSLEngineResult res;
    try {
      res = engine.unwrap(netIn, appIn);
    } finally {
      appIn.flip();
      netIn.compact();
    }
    switch (res.getStatus()) {
      case OK:
        return res.bytesProduced();
      case BUFFER_UNDERFLOW:
        if (!netIn.hasRemaining()) { // a record bigger than our buffer
          netIn = grow(netIn, engine.getSession().getPacketBufferSize(), false);
        }
        return UNDERFLOW;
      case BUFFER_OVERFLOW:
        appIn = grow(appIn, engine.getSession().getApplicationBufferSize(), true);
        return 0;
      case CLOSED:
      default:
        return CLOSED;
    }
  }

  private CompletableFuture<Integer> fillNetIn() {
    return readRaw(netIn);
  }

  // ---------------------------------------------------------------- write

  @Override
  public CompletableFuture<Void> writeFully(ByteBuffer src) {
    if (!src.hasRemaining()) {
      return CompletableFuture.completedFuture(null);
    }
    return wrapAndSend(src)
        .thenCompose(v -> handshaking() ? drive() : CompletableFuture.<Void>completedFuture(null))
        .thenCompose(v -> writeFully(src));
  }

  /** Encrypt as much of {@code src} as fits in netOut (several records), then send it. */
  private CompletableFuture<Void> wrapAndSend(ByteBuffer src) {
    try {
      int packet = engine.getSession().getPacketBufferSize();
      netOut.clear();
      while (true) {
        SSLEngineResult res = engine.wrap(src, netOut);
        SSLEngineResult.Status st = res.getStatus();
        if (st == SSLEngineResult.Status.BUFFER_OVERFLOW) {
          if (netOut.position() == 0) {
            netOut = ByteBuffer.allocateDirect(Math.max(netOut.capacity() * 2, packet));
            continue;
          }
          break; // send what we have, wrap the rest next round
        }
        if (st == SSLEngineResult.Status.CLOSED
            || !src.hasRemaining()
            || netOut.remaining() < packet
            || handshaking()) {
          break;
        }
      }
      netOut.flip();
      return writeRawFully(netOut);
    } catch (SSLException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  // ---------------------------------------------------------------- close

  /** Send TLS close_notify (best effort), then close the socket. */
  @Override
  public void close() {
    if (!closing.compareAndSet(false, true)) {
      return;
    }
    try {
      engine.closeOutbound();
      ByteBuffer bye = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
      engine.wrap(EMPTY, bye);
      bye.flip();
      writeRawFully(bye).whenComplete((v, e) -> super.close());
    } catch (Exception e) {
      super.close();
    }
  }

  private static ByteBuffer grow(ByteBuffer buf, int atLeast, boolean drainMode) {
    ByteBuffer bigger = ByteBuffer.allocate(Math.max(buf.capacity() * 2, atLeast + buf.capacity()));
    if (!drainMode) {
      buf.flip();
    }
    bigger.put(buf);
    if (drainMode) {
      bigger.flip();
    }
    return bigger;
  }
}
