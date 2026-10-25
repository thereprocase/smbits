package dev.gridline.files;

import android.app.ActivityManager;
import android.content.*;
import android.net.*;
import android.os.Build;
import android.telephony.*;
import dev.gridline.files.core.*;
import java.util.*;

/** Private on-device history. Never binds sockets, changes VPN routing, or records credentials. */
public final class TuningMemory {
  private final Context context;
  private final SharedPreferences settings, history;
  private volatile NetworkCapabilities capabilities;
  private volatile long generation;
  private volatile Network currentNetwork;
  private final java.util.concurrent.atomic.AtomicLong resets =
      new java.util.concurrent.atomic.AtomicLong();
  private final ConnectivityManager.NetworkCallback callback =
      new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
          currentNetwork = network;
          capabilities = null;
          generation++;
        }
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
          if (network.equals(currentNetwork))
            capabilities = caps;
        }
        @Override
        public void onLost(Network network) {
          if (network.equals(currentNetwork)) {
            currentNetwork = null;
            capabilities = null;
            generation++;
          }
        }
      };
  public TuningMemory(Context context) {
    this.context = context;
    settings = context.getSharedPreferences("tuning-settings", 0);
    history = context.getSharedPreferences("link-history-v1", 0);
    context.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback);
  }
  public SharedPreferences settings() {
    return settings;
  }
  public synchronized void clear() {
    history.edit().clear().apply();
    resets.incrementAndGet();
  }
  private record Link(String key, String label, long generation, boolean remember) {}
  private Link link(boolean remote) {
    if (!remote)
      return new Link("local", "Local storage", resets.get(), true);
    NetworkCapabilities c = capabilities;
    if (c == null)
      return new Link("unknown", "Link not identified", generation + resets.get(), false);
    boolean vpn = c.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    boolean cell = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    boolean wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    String bearer = cell                                         ? "Cellular"
        : wifi                                                   ? "Wi-Fi"
        : c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                                                                 : "Other link";
    int quality = 0;
    if (cell) {
      try {
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        int subscription = SubscriptionManager.getDefaultDataSubscriptionId();
        if (subscription >= 0)
          tm = tm.createForSubscriptionId(subscription);
        SignalStrength signal = tm.getSignalStrength();
        if (signal != null)
          quality = signal.getLevel();
        if (Build.VERSION.SDK_INT >= 33) {
          int radio = tm.getDataNetworkType();
          bearer += radio == TelephonyManager.NETWORK_TYPE_NR ? " 5G NR"
              : radio == TelephonyManager.NETWORK_TYPE_LTE    ? " LTE"
                                                              : " radio " + radio;
        }
      } catch (SecurityException | UnsupportedOperationException ignored) {
        // Radio detail is optional. Do not request sensitive permissions to obtain it.
      }
    } else if (wifi && Build.VERSION.SDK_INT >= 29) {
      int dbm = c.getSignalStrength();
      if (dbm != Integer.MIN_VALUE && dbm < 0)
        quality = dbm >= -60 ? 4 : dbm >= -70 ? 3 : dbm >= -80 ? 2 : 1;
    }
    int override = settings.getInt("quality", 0);
    if (override > 0)
      quality = override;
    String[] qualities = {
        "signal unknown", "weak signal", "fair signal", "good signal", "excellent signal"};
    quality = Math.max(0, Math.min(4, quality));
    String label = settings.getString("route", "").trim();
    boolean metered = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    String description = bearer + (vpn ? " · VPN" : " · direct")
        + (label.isEmpty() ? "" : " · " + label) + " · " + qualities[quality]
        + (metered ? " · metered" : "");
    if (vpn && label.isEmpty())
      description += " · add route label to remember";
    return new Link(
        bearer + "|vpn=" + vpn + "|metered=" + metered + "|quality=" + quality + "|label=" + label,
        description, generation + resets.get(), !vpn || !label.isEmpty());
  }
  public String description() {
    int count = 0;
    long now = System.currentTimeMillis();
    for (String key : history.getAll().keySet()) {
      LinkHistory.Profile p = read(key);
      if (p != null && p.updated() <= now && now - p.updated() < 30 * LinkHistory.DAY)
        count++;
    }
    return link(true).label + " · " + count + " remembered contexts";
  }
  public void attach(Transfer.Job job) {
    job.lowMemory = () -> {
      ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
      context.getSystemService(ActivityManager.class).getMemoryInfo(info);
      return info.lowMemory;
    };
    job.feedback = new Session();
  }
  private static String storeKey(Store store) {
    return store instanceof SmbStore smb ? smb.tuningNamespace() : store.id();
  }
  private LinkHistory.Profile read(String key) {
    try {
      String value = history.getString(key, "");
      String[] fields = value.split(",");
      if (fields.length != 5)
        return null;
      int workers = Integer.parseInt(fields[0]), samples = Integer.parseInt(fields[4]);
      double rate = Double.parseDouble(fields[1]), latency = Double.parseDouble(fields[2]);
      long updated = Long.parseLong(fields[3]);
      if (workers < 1 || workers > 128 || samples < 3 || !Double.isFinite(rate)
          || !Double.isFinite(latency) || rate < 0 || latency < 0)
        return null;
      return new LinkHistory.Profile(workers, rate, latency, updated, samples);
    } catch (RuntimeException invalid) {
      return null;
    }
  }
  private synchronized void save(String key, LinkHistory.Profile profile, long expectedReset) {
    if (profile == null || resets.get() != expectedReset)
      return;
    // Bound storage and discard stale records. SHA keys obscure names, but are not encryption.
    Map<String, ?> all = history.getAll();
    SharedPreferences.Editor edit = history.edit();
    long now = System.currentTimeMillis();
    List<Map.Entry<String, LinkHistory.Profile>> valid = new ArrayList<>();
    for (String existing : all.keySet()) {
      LinkHistory.Profile p = read(existing);
      if (p == null || now < p.updated() || now - p.updated() >= 30 * LinkHistory.DAY)
        edit.remove(existing);
      else if (!existing.equals(key))
        valid.add(new AbstractMap.SimpleEntry<>(existing, p));
    }
    valid.sort(Comparator.comparingLong(e -> e.getValue().updated()));
    for (int i = 0; i < valid.size() - 127; i++) edit.remove(valid.get(i).getKey());
    edit.putString(key,
            profile.workers() + "," + profile.rate() + "," + profile.latency() + ","
                + profile.updated() + "," + profile.samples())
        .apply();
  }
  private final class Session implements Transfer.Feedback {
    private String key;
    private long networkGeneration, resetGeneration;
    private LinkHistory learner;
    private boolean remember;
    private Link current(Transfer.Job job) {
      return link(job.source instanceof SmbStore || job.destination instanceof SmbStore);
    }
    private String key(Transfer.Job job, Link link) {
      return LinkHistory.contextKey(link.key, storeKey(job.source), storeKey(job.destination),
          job.move ? "move" : "copy", LinkHistory.workload(job.totalBytes, job.totalFiles));
    }
    private void select(Transfer.Job job, Link link, String nextKey) {
      key = nextKey;
      networkGeneration = link.generation;
      resetGeneration = resets.get();
      remember = link.remember;
      long now = System.currentTimeMillis();
      LinkHistory.Profile prior = remember ? read(key) : null;
      learner = new LinkHistory(prior, now);
      int start = learner.startingWorkers(now);
      job.controller.seed(start);
      job.tuningContext = link.label + " · "
          + (learner.profile() == null ? "new profile" : "saved profile") + " · start "
          + job.controller.workers() + " · " + LinkHistory.workload(job.totalBytes, job.totalFiles);
    }
    @Override
    public void start(Transfer.Job job) {
      Link link = current(job);
      select(job, link, key(job, link));
    }
    @Override
    public void sample(
        Transfer.Job job, int workers, double rate, boolean demand, boolean trouble) {
      Link link = current(job);
      String next = key(job, link);
      if (!next.equals(key) || networkGeneration != link.generation) {
        // Discard the window spanning a handover, pause, or explicit history reset.
        select(job, link, next);
        return;
      }
      if (job.capBytes != 0 || job.pause)
        return;
      learner.observe(workers, rate, job.p95, demand, trouble, System.currentTimeMillis());
      if (remember && trouble)
        save(key, learner.profile(), resetGeneration);
    }
    @Override
    public void finish(Transfer.Job job) {
      if (learner == null)
        return;
      Link link = current(job);
      if (remember && job.state == Transfer.State.DONE && key.equals(key(job, link))
          && networkGeneration == link.generation)
        save(key, learner.profile(), resetGeneration);
    }
  }
}
