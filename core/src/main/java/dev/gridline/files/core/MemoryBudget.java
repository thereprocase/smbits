package dev.gridline.files.core;

/** Conservative admission estimate, not a guarantee about native or provider allocations. */
public final class MemoryBudget {
  public static final int MAX_WORKERS = 128;
  private static final long MIB = 1024L * 1024;
  private MemoryBudget() {}
  public static int maximum(int requested) {
    return requested == 0 ? MAX_WORKERS : Math.max(1, Math.min(MAX_WORKERS, requested));
  }
  public static int ceiling(int requested, long heapMax, long heapUsed, int active, boolean low) {
    if (low)
      return 1;
    long reserve = Math.max(32 * MIB, heapMax / 4);
    long available = heapMax - heapUsed - reserve;
    if (available <= 0)
      return 1;
    // Include existing workers; their buffers are already reflected in heapUsed.
    long capacity = Math.max(0, active) + available / (4 * MIB);
    return (int) Math.max(1, Math.min(maximum(requested), capacity));
  }
  public static int current(int requested, int active, boolean low) {
    Runtime r = Runtime.getRuntime();
    return ceiling(requested, r.maxMemory(), r.totalMemory() - r.freeMemory(), active, low);
  }
}
