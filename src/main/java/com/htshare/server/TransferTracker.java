package com.htshare.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps track of who is downloading what, for the desktop "Transfers" panel.
 *
 * <p>One row per (device, file). Browsers often fetch a file in several pieces — a video player
 * asks for byte ranges as you seek, a resumed download asks for "the rest" — so those requests
 * are merged into the same row instead of each becoming a new one.
 *
 * <p>Thread-safe: the server's I/O threads report progress while the UI thread reads snapshots.
 */
public final class TransferTracker {

  public enum State {
    ACTIVE,
    DONE,
    STOPPED
  }

  /** Immutable view of one row, for the UI. */
  public record Snapshot(
      long id,
      String fileName,
      String device,
      long size,
      long sent,
      double progress,
      double bytesPerSecond,
      State state) {}

  /** Returned by {@link #begin}; the server reports progress through it. */
  public final class Handle {
    private final Entry entry;
    private long position;
    private boolean ended;

    private Handle(Entry entry, long startOffset) {
      this.entry = entry;
      this.position = startOffset;
    }

    /** {@code n} more bytes have been handed to the network. */
    public void progress(int n) {
      position += n;
      entry.sent.addAndGet(n);
      entry.reached(position);
      totalBytesSent.addAndGet(n);
    }

    /** The request finished, completely or not. */
    public void end() {
      if (!ended) {
        ended = true;
        entry.inFlight.decrementAndGet();
        entry.lastActivity = System.currentTimeMillis();
      }
    }
  }

  private static final int MAX_ROWS = 30;
  /** A finished row is reused for a new request on the same file within this window. */
  private static final long MERGE_WINDOW_MS = 10_000;

  private final Map<String, Entry> rows = new LinkedHashMap<>();
  private final AtomicLong nextId = new AtomicLong(1);
  private final AtomicLong totalBytesSent = new AtomicLong();
  private final AtomicInteger filesSent = new AtomicInteger();

  /** Start tracking one file request. Call {@link Handle#end()} when the response is over. */
  public synchronized Handle begin(
      String relativePath, String fileName, String device, long size, long startOffset) {
    String key = device + '\n' + relativePath;
    long now = System.currentTimeMillis();
    Entry e = rows.get(key);
    boolean reuse =
        e != null
            && (e.inFlight.get() > 0
                || e.state() != State.DONE
                || now - e.lastActivity < MERGE_WINDOW_MS);
    if (!reuse) {
      if (e != null) {
        rows.remove(key);
      }
      e = new Entry(nextId.getAndIncrement(), fileName, device, size);
      filesSent.incrementAndGet();
    } else {
      rows.remove(key); // re-insert so it moves to the newest position
    }
    rows.put(key, e);
    e.inFlight.incrementAndGet();
    e.lastActivity = now;
    prune();
    return new Handle(e, startOffset);
  }

  /** Newest first. Also updates each row's transfer speed. */
  public synchronized List<Snapshot> snapshot() {
    long now = System.currentTimeMillis();
    List<Snapshot> out = new ArrayList<>(rows.size());
    for (Entry e : rows.values()) {
      out.add(0, e.snapshot(now));
    }
    return out;
  }

  public long totalBytesSent() {
    return totalBytesSent.get();
  }

  /** Distinct (device, file) transfers started. */
  public int filesSent() {
    return filesSent.get();
  }

  public synchronized int activeCount() {
    int n = 0;
    for (Entry e : rows.values()) {
      if (e.inFlight.get() > 0) n++;
    }
    return n;
  }

  private void prune() {
    Iterator<Entry> it = rows.values().iterator();
    while (rows.size() > MAX_ROWS && it.hasNext()) {
      if (it.next().inFlight.get() == 0) {
        it.remove();
      }
    }
  }

  private static final class Entry {
    final long id;
    final String fileName;
    final String device;
    final long size;
    final AtomicLong sent = new AtomicLong();
    final AtomicLong furthest = new AtomicLong();
    final AtomicInteger inFlight = new AtomicInteger();
    volatile long lastActivity;

    // speed, sampled when the UI asks for a snapshot (about once a second)
    private long sampleBytes;
    private long sampleTime = System.currentTimeMillis();
    private double speed;

    Entry(long id, String fileName, String device, long size) {
      this.id = id;
      this.fileName = fileName;
      this.device = device;
      this.size = size;
    }

    void reached(long position) {
      furthest.accumulateAndGet(position, Math::max);
    }

    State state() {
      if (inFlight.get() > 0) return State.ACTIVE;
      return furthest.get() >= size ? State.DONE : State.STOPPED;
    }

    Snapshot snapshot(long now) {
      long bytes = sent.get();
      long dt = now - sampleTime;
      if (dt >= 500) {
        double instant = (bytes - sampleBytes) * 1000.0 / dt;
        // smooth a little so the number doesn't jitter
        speed = speed == 0 ? instant : speed * 0.4 + instant * 0.6;
        sampleBytes = bytes;
        sampleTime = now;
      }
      State st = state();
      double progress = size <= 0 ? 1.0 : Math.min(1.0, furthest.get() / (double) size);
      return new Snapshot(id, fileName, device, size, bytes, progress, st == State.ACTIVE ? speed : 0, st);
    }
  }
}
