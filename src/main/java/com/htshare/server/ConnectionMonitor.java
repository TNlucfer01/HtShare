package com.htshare.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// what are these concurrent packages do

/** Monitors server connection activity and handles automatic shutdown */
public class ConnectionMonitor {
  private static final Logger logger = LoggerFactory.getLogger(ConnectionMonitor.class);
  // for timout after 5 min
  private final long timeoutMillis;
  // why ?
  private final Runnable onTimeout;
  // why do we need the schedular
  private final ScheduledExecutorService scheduler;
  // what arethese atomic data types
  private final AtomicLong lastAccessTime;
  private final AtomicInteger totalRequests;
  private final AtomicInteger activeConnections;
  private final AtomicInteger totalDownloads;
  // what is volatile?
  private volatile boolean enabled;
  private volatile boolean hasHadActivity;

  /**
   * Create a connection monitor
   *
   * @param timeoutMinutes Minutes of inactivity before shutdown (0 = disabled)
   * @param onTimeout Callback to execute on timeout
   */
  // but this  should  happen when  the user is not using after 5 min
  //// not important
  public ConnectionMonitor(int timeoutMinutes, Runnable onTimeout) {
    this.timeoutMillis = timeoutMinutes > 0 ? timeoutMinutes * 60 * 1000L : 0;
    // what should  happen on tim eout
    this.onTimeout = onTimeout;
    // what is this ?
    this.scheduler = Executors.newSingleThreadScheduledExecutor();
    // same foe the 4 lines
    this.lastAccessTime = new AtomicLong(System.currentTimeMillis());
    this.totalRequests = new AtomicInteger(0);
    this.activeConnections = new AtomicInteger(0);
    this.totalDownloads = new AtomicInteger(0);
    // on or ont
    this.enabled = timeoutMinutes > 0;
    this.hasHadActivity = false;

    if (enabled) {
      // okay but the question where is te startMonitoring saves the log
      startMonitoring();
      logger.info("Connection monitor enabled with {}min timeout", timeoutMinutes);
    } else {
      logger.info("Connection monitor disabled (no timeout)");
    }
  }

  /** Start monitoring for inactivity */
  private void startMonitoring() {
    // what does this  do ? this is not working properly
    scheduler.scheduleAtFixedRate(
        () -> {
          if (!enabled) return;
          // why fo we need now
          long now = System.currentTimeMillis();

          long lastAccess = lastAccessTime.get();
          long timeSinceLastAccess = now - lastAccess;

          // Only trigger timeout if we've had at least one successful request
          if (hasHadActivity && timeSinceLastAccess >= timeoutMillis) {
            logger.info("Inactivity timeout reached ({}ms since last access)", timeSinceLastAccess);
            logger.info(
                "Statistics - Total Requests: {}, Downloads: {}",
                totalRequests.get(),
                totalDownloads.get());

            enabled = false; // Prevent multiple triggers

            if (onTimeout != null) {
              onTimeout.run();
            }
          } else if (hasHadActivity) {
            long remainingSeconds = (timeoutMillis - timeSinceLastAccess) / 1000;
            logger.debug(
                "Time until auto-shutdown: {}s (Total requests: {})",
                remainingSeconds,
                totalRequests.get());
          }
        },
        30,
        30,
        TimeUnit.SECONDS); // Check every 30 seconds
  }

  /** Record a new request */
  // why do we need to recover
  public void recordRequest(String path, String method) {
    lastAccessTime.set(System.currentTimeMillis());
    totalRequests.incrementAndGet();
    hasHadActivity = true;

    logger.debug("Request recorded: {} {} (Total: {})", method, path, totalRequests.get());
  }

  /** Record a file download why record them */
  public void recordDownload(String filename, long bytes) {
    lastAccessTime.set(System.currentTimeMillis());
    totalDownloads.incrementAndGet();
    hasHadActivity = true;

    logger.info(
        "Download recorded: {} ({} bytes) - Total downloads:{}",
        filename,
        bytes,
        totalDownloads.get());
  }

  /** Record connection opened */
  public void connectionOpened() {
    int count = activeConnections.incrementAndGet();
    logger.debug("Connection opened. Active connections: {}", count);
  }

  /** Record connection closed */
  public void connectionClosed() {
    int count = activeConnections.decrementAndGet();
    logger.debug("Connection closed. Active connections: {}", count);
  }

  /** Get time since last activity in seconds */
  public long getTimeSinceLastActivity() {
    return (System.currentTimeMillis() - lastAccessTime.get()) / 1000;
  }

  /** Get remaining time before timeout in seconds */
  public long getRemainingTime() {
    if (!enabled || timeoutMillis == 0) {
      return -1; // No timeout
    }

    long elapsed = System.currentTimeMillis() - lastAccessTime.get();
    long remaining = timeoutMillis - elapsed;
    return Math.max(0, remaining / 1000);
  }

  /** Get connection statistics */
  public ConnectionStats getStats() {
    return new ConnectionStats(
        totalRequests.get(),
        totalDownloads.get(),
        activeConnections.get(),
        getTimeSinceLastActivity(),
        hasHadActivity);
  }

  /** Reset the timeout timer */
  public void resetTimer() {
    lastAccessTime.set(System.currentTimeMillis());
    logger.debug("Timeout timer reset");
  }

  /** Enable or disable the monitor */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    if (enabled) {
      resetTimer();
    }
    logger.info("Connection monitor {}", enabled ? "enabled" : "disabled");
  }

  /** Check if monitor is enabled */
  public boolean isEnabled() {
    return enabled;
  }

  /** Shutdown the monitor */
  public void shutdown() {
    enabled = false;
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
    logger.info("Connection monitor shut down");
  }

  /** Connection statistics data class */
  public static class ConnectionStats {
    private final int totalRequests;
    private final int totalDownloads;
    private final int activeConnections;
    private final long secondsSinceLastActivity;
    private final boolean hasHadActivity;

    public ConnectionStats(
        int totalRequests,
        int totalDownloads,
        int activeConnections,
        long secondsSinceLastActivity,
        boolean hasHadActivity) {
      this.totalRequests = totalRequests;
      this.totalDownloads = totalDownloads;
      this.activeConnections = activeConnections;
      this.secondsSinceLastActivity = secondsSinceLastActivity;
      this.hasHadActivity = hasHadActivity;
    }

    public int getTotalRequests() {
      return totalRequests;
    }

    public int getTotalDownloads() {
      return totalDownloads;
    }

    public int getActiveConnections() {
      return activeConnections;
    }

    public long getSecondsSinceLastActivity() {
      return secondsSinceLastActivity;
    }

    public boolean hasHadActivity() {
      return hasHadActivity;
    }

    @Override
    public String toString() {
      return String.format(
          "Stats[requests=%d, downloads=%d, active=%d, idle=%ds]",
          totalRequests, totalDownloads, activeConnections, secondsSinceLastActivity);
    }
  }
}

