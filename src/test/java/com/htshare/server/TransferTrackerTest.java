package com.htshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TransferTrackerTest {

  @Test
  void rangeRequestsForTheSameFileShareOneRow() {
    TransferTracker t = new TransferTracker();
    // a video player fetching the start, then seeking to the end
    TransferTracker.Handle a = t.begin("v.mp4", "v.mp4", "Android · 1.2.3.4", 1000, 0);
    a.progress(300);
    a.end();
    TransferTracker.Handle b = t.begin("v.mp4", "v.mp4", "Android · 1.2.3.4", 1000, 700);
    b.progress(300);
    b.end();

    List<TransferTracker.Snapshot> rows = t.snapshot();
    assertEquals(1, rows.size());
    assertEquals(1, t.filesSent());
    assertEquals(TransferTracker.State.DONE, rows.get(0).state()); // reached the end
    assertEquals(600, t.totalBytesSent());
  }

  @Test
  void statesFollowProgress() {
    TransferTracker t = new TransferTracker();
    TransferTracker.Handle h = t.begin("a.zip", "a.zip", "Mac · 1.2.3.5", 1000, 0);
    h.progress(400);
    assertEquals(TransferTracker.State.ACTIVE, t.snapshot().get(0).state());
    assertEquals(1, t.activeCount());
    h.end(); // client went away at 40%
    assertEquals(TransferTracker.State.STOPPED, t.snapshot().get(0).state());
    assertEquals(0.4, t.snapshot().get(0).progress(), 1e-9);
  }

  @Test
  void differentDevicesGetTheirOwnRows() {
    TransferTracker t = new TransferTracker();
    t.begin("f", "f", "iPhone · 1.1.1.1", 10, 0).end();
    t.begin("f", "f", "Android · 2.2.2.2", 10, 0).end();
    assertEquals(2, t.snapshot().size());
    assertEquals(2, t.filesSent());
  }
}
