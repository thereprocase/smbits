package dev.gridline.files.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Local, expiring prior for a specific route/root/direction/workload context. */
public final class LinkHistory {
  public static final long DAY = 86_400_000L;
  public record Profile(int workers, double rate, double latency, long updated, int samples) {
    public int startingWorkers(long now) {
      long age = now - updated;
      if (age < 0 || age >= 30 * DAY || samples < 3)
        return 2;
      double weight = Math.pow(.5, age / (7.0 * DAY));
      return Math.max(
          1, Math.min(MemoryBudget.MAX_WORKERS, (int) Math.round(2 + (workers - 2) * weight)));
    }
  }
  private Profile best;
  private int candidate, windows, observedWindows;
  private boolean measured;
  private double rate, latency;
  public LinkHistory(Profile prior, long now) {
    if (prior != null && prior.samples >= 3 && now >= prior.updated
        && now - prior.updated < 30 * DAY)
      best = prior;
  }
  public int startingWorkers(long now) {
    return best == null ? 2 : best.startingWorkers(now);
  }
  public Profile profile() {
    return best;
  }
  public void observe(
      int workers, double bytesPerSecond, double p95, boolean demand, boolean trouble, long now) {
    if (demand && bytesPerSecond > 0 && p95 > 0)
      observedWindows++;
    if (trouble) {
      windows = 0;
      measured = false;
      // Back off an existing prior immediately; a single successful window cannot erase it.
      if (best != null || observedWindows >= 3)
        best =
            new Profile(Math.max(1, Math.min(best == null ? workers : best.workers, workers / 2)),
                0, 0, now, 3);
      return;
    }
    if (!demand || bytesPerSecond <= 0 || p95 <= 0) {
      windows = 0;
      return;
    }
    if (candidate != workers) {
      candidate = workers;
      windows = 0;
      rate = latency = 0;
    }
    windows++;
    rate += (bytesPerSecond - rate) / windows;
    latency += (p95 - latency) / windows;
    if (windows < 3)
      return;
    // Prefer fewer workers when within 5% of the best observed rate.
    if (!measured || best == null || best.rate == 0 || rate > best.rate * 1.05
        || (workers <= best.workers && rate >= best.rate * .95)) {
      best = new Profile(workers, rate, latency, now, Math.min(1000, windows));
      measured = true;
    }
  }
  public static String contextKey(String... fields) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String field : fields) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      StringBuilder hex = new StringBuilder();
      for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
  public static String workload(long bytes, long files) {
    long mean = files == 0 ? 0 : bytes / files;
    return mean < 256 * 1024 ? "small" : mean < 8 * 1024 * 1024 ? "mixed" : "large";
  }
}
