package dev.gridline.files.core;

import static org.junit.Assert.*;

import org.junit.Test;

public final class TuningTest {
  @Test
  public void noisyNewLinksRememberConservativeStartsAfterRepeatedEvidence() {
    LinkHistory h = new LinkHistory(null, NOW);
    h.observe(4, 1000, 500, true, true, NOW);
    h.observe(4, 1000, 500, true, true, NOW);
    assertNull(h.profile());
    h.observe(4, 1000, 500, true, true, NOW);
    assertEquals(2, h.startingWorkers(NOW));
    h.observe(2, 1000, 500, true, true, NOW);
    assertEquals(1, h.startingWorkers(NOW));
  }

  @Test
  public void profileKeysSeparateRoutesRootsDirectionsAndFieldBoundaries() {
    String key = LinkHistory.contextKey("5G/X/excellent", "local", "smb://y/share", "large");
    assertEquals(key, LinkHistory.contextKey("5G/X/excellent", "local", "smb://y/share", "large"));
    assertNotEquals(
        key, LinkHistory.contextKey("5G/Z/excellent", "local", "smb://y/share", "large"));
    assertNotEquals(key, LinkHistory.contextKey("5G/X/weak", "local", "smb://y/share", "large"));
    assertNotEquals(
        key, LinkHistory.contextKey("5G/X/excellent", "local", "smb://y/other", "large"));
    assertNotEquals(
        key, LinkHistory.contextKey("5G/X/excellent", "smb://y/share", "local", "large"));
    assertNotEquals(LinkHistory.contextKey("a|b", "c"), LinkHistory.contextKey("a", "b|c"));
  }

  @Test
  public void probesReturnToFewerWorkersWhenSpeedDoesNotImprove() {
    AdaptiveController c = new AdaptiveController(128);
    for (int i = 0; i < 60; i++) {
      c.sample(1e6, 10, 0, false, true);
      assertTrue(c.workers() <= 3);
    }
  }
  @Test
  public void probesRejectMinorSpeedGainAtLargeLatencyCost() {
    AdaptiveController c = new AdaptiveController(128);
    c.sample(1e6, 10, 0, false, true);
    assertEquals(3, c.workers());
    c.sample(1.1e6, 30, 0, false, true);
    c.sample(1.1e6, 30, 0, false, true);
    assertEquals(2, c.workers());
  }
  @Test
  public void probesKeepMeaningfulThroughputGain() {
    AdaptiveController c = new AdaptiveController(128);
    c.sample(1e6, 10, 0, false, true);
    c.sample(1.3e6, 12, 0, false, true);
    c.sample(1.3e6, 12, 0, false, true);
    assertEquals(3, c.workers());
  }

  private static final long MIB = 1024L * 1024, NOW = 100 * LinkHistory.DAY;
  @Test
  public void autoCeilingAccountsForHeapAndReservesUiHeadroom() {
    assertEquals(32, MemoryBudget.ceiling(0, 256 * MIB, 64 * MIB, 0, false));
    assertEquals(34, MemoryBudget.ceiling(0, 256 * MIB, 64 * MIB, 2, false));
    assertEquals(8, MemoryBudget.ceiling(8, 256 * MIB, 64 * MIB, 2, false));
    assertEquals(1, MemoryBudget.ceiling(0, 256 * MIB, 240 * MIB, 20, false));
    assertEquals(1, MemoryBudget.ceiling(0, 1024 * MIB, 0, 0, true));
    assertEquals(128, MemoryBudget.ceiling(0, 1024 * MIB, 0, 0, false));
  }
  @Test
  public void resourcePressureBoundsLearnedStartAndLiveWorkers() {
    AdaptiveController c = new AdaptiveController(0);
    c.seed(90);
    assertEquals(90, c.workers());
    c.constrain(7);
    assertEquals(7, c.workers());
    c.seed(60);
    assertEquals(7, c.workers());
    c.sample(10000, 10, 800, false, true);
    assertEquals(3, c.workers());
  }
  @Test
  public void idleQueueCannotGrowOrInterpretDrainingAsCongestion() {
    AdaptiveController c = new AdaptiveController(128);
    c.seed(12);
    for (int i = 1; i <= 20; i++) c.sample(10000.0 / i, 10, 0, false, false);
    assertEquals(12, c.workers());
    c.sample(10, 10, 0, true, false);
    assertEquals(6, c.workers());
  }
  @Test
  public void familiarLinkStartsConsistentlyAndAgesTowardTwo() {
    LinkHistory.Profile p = new LinkHistory.Profile(18, 900000, 20, NOW, 9);
    assertEquals(18, new LinkHistory(p, NOW).startingWorkers(NOW));
    assertEquals(10,
        new LinkHistory(p, NOW + 7 * LinkHistory.DAY).startingWorkers(NOW + 7 * LinkHistory.DAY));
    assertEquals(2, p.startingWorkers(NOW + 30 * LinkHistory.DAY));
    assertEquals(2, p.startingWorkers(NOW - 1));
  }
  @Test
  public void onlySustainedQueuedDemandTeachesConcurrency() {
    LinkHistory h = new LinkHistory(null, NOW);
    for (int i = 0; i < 10; i++) h.observe(30, 1e6, 20, false, false, NOW);
    assertNull(h.profile());
    h.observe(8, 1e6, 20, true, false, NOW);
    h.observe(8, 1e6, 20, true, false, NOW);
    assertNull(h.profile());
    h.observe(8, 1e6, 20, true, false, NOW);
    assertEquals(8, h.startingWorkers(NOW));
  }
  @Test
  public void comparableSpeedPrefersFewerWorkersAndFreshConditionsReplaceOldRates() {
    LinkHistory h = new LinkHistory(new LinkHistory.Profile(20, 10e6, 10, NOW, 9), NOW);
    for (int i = 0; i < 3; i++) h.observe(10, 1e6, 20, true, false, NOW);
    assertEquals(10, h.startingWorkers(NOW));
    for (int i = 0; i < 3; i++) h.observe(6, .97e6, 20, true, false, NOW);
    assertEquals(6, h.startingWorkers(NOW));
    for (int i = 0; i < 3; i++) h.observe(12, 1e6, 20, true, false, NOW);
    assertEquals(6, h.startingWorkers(NOW));
  }
  @Test
  public void failureBacksOffSavedPriorAndRequiresNewEvidence() {
    LinkHistory h = new LinkHistory(new LinkHistory.Profile(16, 1e6, 20, NOW, 9), NOW);
    h.observe(16, 0, 1000, true, true, NOW);
    assertEquals(8, h.startingWorkers(NOW));
    h.observe(16, 2e6, 10, true, false, NOW);
    assertEquals(8, h.startingWorkers(NOW));
  }
  @Test
  public void workloadBucketsSeparateTinyFilesFromBulkTransfers() {
    assertEquals("small", LinkHistory.workload(100000, 100));
    assertEquals("mixed", LinkHistory.workload(4 * MIB, 2));
    assertEquals("large", LinkHistory.workload(64 * MIB, 2));
  }
}
