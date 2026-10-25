package dev.gridline.files;

import android.app.Application;
import dev.gridline.files.core.*;
import java.util.*;
import java.util.concurrent.*;

public final class FilesApp extends Application {
  public final List<Transfer.Job> jobs = new CopyOnWriteArrayList<>();
  public final Map<String, Store> connections = new ConcurrentHashMap<>();
  public final Set<Store> liveStores = ConcurrentHashMap.newKeySet();
  public final ExecutorService browse = Executors.newFixedThreadPool(2);
  public final ExecutorService operations = Executors.newSingleThreadExecutor();
  public volatile boolean mutation;
  public TuningMemory tuning;
  @Override
  public void onCreate() {
    super.onCreate();
    tuning = new TuningMemory(this);
  }
  public boolean busy() {
    for (Transfer.Job j : jobs)
      if (j.state == Transfer.State.QUEUED || j.state == Transfer.State.RUNNING
          || j.state == Transfer.State.SCANNING || j.state == Transfer.State.PAUSED)
        return true;
    return false;
  }
  public void browsingLatency(double ms) {
    for (Transfer.Job j : jobs) j.browseMs = ms;
  }
}
