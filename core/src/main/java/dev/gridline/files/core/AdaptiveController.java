package dev.gridline.files.core;

/** Bounded hill-climbing: grow only with queued demand and tolerable I/O latency. */
public final class AdaptiveController {
  private int workers = 2, cooldown, stableWindows, ceiling;
  private final int max;
  private double previousRate, baselineMs, probeRate, probeLatency, probeTotal;
  private int probeFrom, probeWindows;
  private boolean pressure;
  private String reason = "Measuring transfer";
  public AdaptiveController(int maximum) {
    max = MemoryBudget.maximum(maximum);
    ceiling = max;
    workers = Math.min(2, max);
  }
  public synchronized int workers() {
    return workers;
  }
  public synchronized int ceiling() {
    return ceiling;
  }
  public synchronized String reason() {
    return reason;
  }
  public synchronized boolean pressure() {
    return pressure;
  }
  public synchronized void constrain(int available) {
    ceiling = Math.max(1, Math.min(max, available));
    if (workers > ceiling) {
      workers = ceiling;
      probeFrom = 0;
      cooldown = 2;
      reason = "Limiting workers for memory headroom";
    }
  }
  public synchronized void seed(int learned) {
    workers = Math.max(1, Math.min(ceiling, learned));
    cooldown = 2;
    stableWindows = 0;
    previousRate = baselineMs = 0;
    probeFrom = 0;
    reason = "Checking starting concurrency";
  }
  public synchronized int sample(double rate, double p95, double browse, boolean errors) {
    return sample(rate, p95, browse, errors, true);
  }
  public synchronized int sample(
      double rate, double p95, double browse, boolean errors, boolean demand) {
    if (p95 > 0)
      baselineMs = baselineMs == 0 ? p95 : Math.min(baselineMs * 1.02, p95);
    pressure = browse > 300 || p95 > Math.max(250, baselineMs * 2.5)
        || (demand && previousRate > 0 && rate < previousRate * .72);
    if (errors || pressure) {
      workers = Math.max(1, workers / 2);
      probeFrom = 0;
      cooldown = 2;
      stableWindows = 0;
      reason = errors ? "Backing off after an error" : "Reducing I/O pressure";
    } else if (!demand) {
      if (probeFrom > 0)
        workers = Math.min(ceiling, probeFrom);
      probeFrom = 0;
      stableWindows = 0;
      reason = "Holding concurrency for current workload";
    } else if (probeFrom > 0) {
      probeTotal += rate;
      if (++probeWindows >= 2) {
        double mean = probeTotal / probeWindows;
        if (mean < probeRate * 1.04 || (p95 > probeLatency * 1.5 && mean < probeRate * 1.25)) {
          workers = Math.min(ceiling, probeFrom);
          cooldown = 4;
          reason = "More workers did not improve the balance";
        } else {
          cooldown = 1;
          reason = "Keeping improved throughput";
        }
        probeFrom = 0;
        stableWindows = 0;
      } else
        reason = "Measuring additional worker";
    } else if (cooldown > 0) {
      cooldown--;
      reason = "Settling";
    } else if (rate > 0 && (previousRate == 0 || rate > previousRate * 1.04)) {
      probe(rate, p95);
    } else {
      reason = "Holding stable throughput";
      if (rate > 0 && ++stableWindows >= 4) {
        probe(rate, p95);
      }
    }
    previousRate = demand ? rate : 0;
    return workers;
  }
  private void probe(double rate, double latency) {
    stableWindows = 0;
    if (workers >= ceiling) {
      reason = "Worker ceiling";
      return;
    }
    probeFrom = workers;
    probeRate = rate;
    probeLatency = Math.max(1, latency);
    probeTotal = 0;
    probeWindows = 0;
    workers++;
    reason = "Testing more parallel files";
  }
}
