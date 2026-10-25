package dev.gridline.files.core;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One queued job at a time, bounded parallel files, staged writes and verified publication. */
public final class Transfer {
  public enum Conflict { STOP, SKIP, KEEP_BOTH, REPLACE }
  public enum State { QUEUED, SCANNING, RUNNING, PAUSED, DONE, ATTENTION, CANCELED }
  public interface Feedback {
    void start(Job job);
    void sample(Job job, int workers, double rate, boolean demand, boolean trouble);
    void finish(Job job);
  }
  public static final class Job {
    public final String id = UUID.randomUUID().toString();
    public final Store source, destination;
    public final List<Store.Entry> items;
    public final String target;
    public final boolean move;
    public final Conflict conflict;
    public final AdaptiveController controller;
    public final long capBytes;
    public final int requestedWorkers;
    public Feedback feedback;
    public java.util.function.BooleanSupplier lowMemory = () -> false;
    public volatile String tuningContext = "No saved link profile";
    public volatile State state = State.QUEUED;
    public volatile boolean cancel, pause;
    private volatile boolean scanning;
    public volatile long totalBytes, totalFiles;
    public volatile double rate, p95, browseMs;
    public final AtomicLong copied = new AtomicLong(), completed = new AtomicLong(),
                            skipped = new AtomicLong();
    public final AtomicLong ioBytes = new AtomicLong();
    public final AtomicInteger activeWorkers = new AtomicInteger();
    public final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<Double> latencies = new ArrayList<>();
    private long nextSlot;
    public Job(Store source, List<Store.Entry> items, Store destination, String target,
        boolean move, Conflict conflict, int workers, long capBytes) {
      this.source = source;
      this.items = Collections.unmodifiableList(new ArrayList<>(items));
      this.destination = destination;
      this.target = target;
      this.move = move;
      this.conflict = conflict;
      this.requestedWorkers = workers;
      this.controller = new AdaptiveController(workers);
      this.capBytes = capBytes;
    }
    public void cancel() {
      cancel = true;
      pause = false;
    }
    public boolean checkpoint() throws IOException {
      boolean waited = false;
      while (pause && !cancel) {
        waited = true;
        state = State.PAUSED;
        rate = 0;
        sleep(80);
      }
      if (cancel || Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Transfer canceled");
      state = scanning ? State.SCANNING : State.RUNNING;
      return waited;
    }
    private static void sleep(long ms) throws InterruptedIOException {
      try {
        Thread.sleep(ms);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Transfer interrupted");
      }
    }
    private long throttle(int bytes) throws IOException {
      if (capBytes <= 0)
        return 0;
      long started = System.nanoTime();
      long due;
      synchronized (this) {
        long now = System.nanoTime();
        due = Math.max(now, nextSlot);
        nextSlot = due + (long) (bytes * 1e9 / capBytes);
      }
      while (System.nanoTime() < due) {
        checkpoint();
        sleep(Math.min(50, Math.max(1, (due - System.nanoTime()) / 1_000_000)));
      }
      return System.nanoTime() - started;
    }
    private synchronized void latency(long start) {
      if (latencies.size() < 4096)
        latencies.add((System.nanoTime() - start) / 1e6);
    }
    private synchronized double sampleLatency() {
      if (latencies.isEmpty())
        return 0;
      Collections.sort(latencies);
      double value = latencies.get(Math.min(latencies.size() - 1, (int) (latencies.size() * .95)));
      latencies.clear();
      return value;
    }
  }
  private record Task(Store.Entry entry, String folder, String name) {}
  private final List<Task> plan = new ArrayList<>();
  private final List<Store.Entry> directories = new ArrayList<>();

  public void run(Job job) {
    job.controller.constrain(
        MemoryBudget.current(job.requestedWorkers, 0, job.lowMemory.getAsBoolean()));
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(job.controller.workers(), MemoryBudget.maximum(job.requestedWorkers),
            15, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    pool.allowCoreThreadTimeOut(true);
    CompletionService<Void> completion = new ExecutorCompletionService<>(pool);
    int active = 0;
    try {
      job.state = State.SCANNING;
      job.scanning = true;
      Set<String> roots = new HashSet<>();
      Set<String> selectedDirectories = new HashSet<>();
      for (Store.Entry item : job.items)
        if (item.directory())
          selectedDirectories.add(item.path().replace('\\', '/'));
      for (Store.Entry item : job.items) {
        if (!roots.add(item.path()))
          throw new IOException("Duplicate source selection");
        String parent = item.path().replace('\\', '/');
        while (parent.contains("/")) {
          parent = parent.substring(0, parent.lastIndexOf('/'));
          if (selectedDirectories.contains(parent))
            throw new IOException(
                "Selection contains a folder and its contents; select the folder alone.");
        }
      }
      for (Store.Entry item : job.items) {
        if (job.source.namespace().equals(job.destination.namespace())
            && (job.target.equals(item.path()) || isDescendant(job, item)))
          throw new IOException("A folder cannot be copied into itself.");
        scan(job, item, job.target, 0);
      }
      job.totalFiles = plan.size();
      if (job.feedback != null)
        job.feedback.start(job);
      pool.setCorePoolSize(job.controller.workers());
      job.scanning = false;
      Iterator<Task> tasks = plan.iterator();
      long last = System.nanoTime(), bytes = job.copied.get(), io = job.ioBytes.get();
      int lastErrors = 0;
      while (tasks.hasNext() || active > 0) {
        if (job.checkpoint()) {
          if (job.feedback != null)
            job.feedback.start(job);
          pool.setCorePoolSize(job.controller.workers());
          last = System.nanoTime();
          bytes = job.copied.get();
          io = job.ioBytes.get();
        }
        while (tasks.hasNext() && active < job.controller.workers()) {
          Task task = tasks.next();
          active++;
          completion.submit(() -> {
            job.activeWorkers.incrementAndGet();
            try {
              copy(job, task);
            } catch (Exception e) {
              if (!job.cancel)
                job.errors.add(task.entry.name() + ": " + safeMessage(e));
            } finally {
              job.activeWorkers.decrementAndGet();
            }
            return null;
          });
        }
        Future<Void> f = completion.poll(100, TimeUnit.MILLISECONDS);
        if (f != null) {
          f.get();
          active--;
        }
        long now = System.nanoTime();
        if (now - last >= 1_000_000_000L) {
          job.rate = (job.copied.get() - bytes) / ((now - last) / 1e9);
          job.p95 = job.sampleLatency();
          double ioRate = (job.ioBytes.get() - io) / ((now - last) / 1e9);
          int usedWorkers = job.controller.workers();
          boolean demand = tasks.hasNext() && active >= usedWorkers && job.capBytes == 0;
          boolean errors = job.errors.size() > lastErrors;
          job.controller.constrain(MemoryBudget.current(
              job.requestedWorkers, job.activeWorkers.get(), job.lowMemory.getAsBoolean()));
          demand &= usedWorkers <= job.controller.ceiling();
          job.controller.sample(ioRate, job.p95, job.browseMs, errors, demand);
          if (job.feedback != null)
            job.feedback.sample(
                job, usedWorkers, ioRate, demand, errors || job.controller.pressure());
          pool.setCorePoolSize(job.controller.workers());
          job.browseMs = 0;
          lastErrors = job.errors.size();
          bytes = job.copied.get();
          io = job.ioBytes.get();
          last = now;
        }
      }
      if (job.move && job.errors.isEmpty() && job.skipped.get() == 0) {
        Collections.reverse(directories);
        for (Store.Entry dir : directories) {
          job.checkpoint();
          if (job.source.list(dir.path()).isEmpty())
            job.source.delete(dir.path(), true);
        }
      }
    } catch (Exception e) {
      if (!job.cancel)
        job.errors.add(safeMessage(e));
    } finally {
      if (active > 0)
        job.cancel();
      pool.shutdown();
      // Do not report terminal state while an in-flight operation can still publish a file.
      try {
        while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        job.errors.add("Waiting for workers was interrupted");
      }
      job.rate = 0;
      double finalLatency = job.sampleLatency();
      if (finalLatency > 0)
        job.p95 = finalLatency;
      job.state = job.cancel                               ? State.CANCELED
          : job.errors.isEmpty() && job.skipped.get() == 0 ? State.DONE
                                                           : State.ATTENTION;
      if (job.feedback != null)
        job.feedback.finish(job);
    }
  }
  private boolean isDescendant(Job j, Store.Entry e) {
    // Providers can use non-path IDs (SAF); the Android UI also checks its navigation ancestry.
    String a = e.path().replace('\\', '/'), b = j.target.replace('\\', '/');
    return e.directory() && b.startsWith(a + "/");
  }
  private void scan(Job j, Store.Entry e, String dest, int depth) throws IOException {
    j.checkpoint();
    if (depth > 128 || plan.size() + directories.size() > 100_000)
      throw new IOException("Job exceeds the scan limit. Select smaller folders.");
    if (e.link())
      throw new IOException(
          "Links and reparse points require a separate explicit policy: " + e.name());
    String name = Store.name(e.name());
    if (e.directory()) {
      directories.add(e);
      String existing = j.destination.child(dest, name);
      if (existing != null && !j.destination.stat(existing).directory())
        throw new IOException("A file occupies folder " + name);
      String folder = existing != null ? existing : j.destination.mkdir(dest, name);
      for (Store.Entry child : j.source.list(e.path())) scan(j, child, folder, depth + 1);
    } else {
      plan.add(new Task(e, dest, name));
      j.totalBytes += Math.max(0, e.size());
    }
  }
  private void copy(Job j, Task task) throws Exception {
    j.checkpoint();
    String name = task.name, temp = null;
    synchronized (j.destination) {
      if (j.destination.exists(task.folder, name)) {
        if (j.conflict == Conflict.SKIP) {
          j.skipped.incrementAndGet();
          return;
        }
        if (j.conflict == Conflict.STOP)
          throw new IOException("Destination exists; choose Skip or Keep both to retry.");
        if (j.conflict == Conflict.KEEP_BOTH)
          name = unique(j.destination, task.folder, name);
        else if (j.destination.stat(j.destination.child(task.folder, name)).directory())
          throw new IOException("Cannot replace a folder with a file");
        if (j.source.namespace().equals(j.destination.namespace())
            && task.entry.path().equals(j.destination.child(task.folder, name)))
          throw new IOException("Source and destination are the same file");
      }
      temp = j.destination.create(task.folder, ".smbfiles-" + UUID.randomUUID() + ".partial");
    }
    try {
      Store.Entry before = j.source.stat(task.entry.path());
      if (before.link() || before.directory())
        throw new IOException("Source type changed");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long size = 0;
      try (InputStream in = j.source.read(before.path());
          OutputStream out = j.destination.write(temp)) {
        byte[] buffer = new byte[256 * 1024];
        int n;
        while (true) {
          j.checkpoint();
          long start = System.nanoTime();
          n = in.read(buffer);
          if (n < 0)
            break;
          if (n == 0)
            continue;
          long intentionalWait = j.throttle(n);
          out.write(buffer, 0, n);
          digest.update(buffer, 0, n);
          size += n;
          j.copied.addAndGet(n);
          j.ioBytes.addAndGet(n);
          j.latency(start + intentionalWait);
        }
        out.flush();
      }
      j.checkpoint();
      Store.Entry after = j.source.stat(before.path());
      if (size != before.size() || before.size() != after.size()
          || before.modified() != after.modified())
        throw new IOException("Source changed during transfer");
      byte[] expected = digest.digest();
      if (!MessageDigest.isEqual(expected, hash(j, j.destination, temp)))
        throw new IOException("Destination SHA-256 verification failed");
      synchronized (j.destination) {
        j.checkpoint();
        String backup = null;
        if (j.destination.exists(task.folder, name)) {
          if (j.conflict == Conflict.KEEP_BOTH)
            name = unique(j.destination, task.folder, name);
          else if (j.conflict == Conflict.REPLACE) {
            String existing = j.destination.child(task.folder, name);
            if (j.destination.stat(existing).directory())
              throw new IOException("Cannot replace a folder with a file");
            backup = j.destination.rename(
                existing, task.folder, ".smbfiles-" + UUID.randomUUID() + ".backup");
          } else
            throw new IOException(
                "Destination appeared while transferring; existing file preserved");
        }
        try {
          j.destination.rename(temp, task.folder, name);
          temp = null;
        } catch (Exception publish) {
          if (backup != null)
            try {
              j.destination.rename(backup, task.folder, name);
            } catch (Exception restore) {
              j.errors.add("Previous destination retained at " + backup);
            }
          throw publish;
        }
        if (backup != null)
          try {
            j.destination.delete(backup, false);
          } catch (Exception cleanup) {
            j.errors.add("New copy verified; previous destination retained at " + backup);
          }
      }
      if (j.move) {
        // Re-read before deletion; metadata alone cannot detect all same-size source edits.
        if (!MessageDigest.isEqual(expected, hash(j, j.source, before.path())))
          throw new IOException("Copy verified; source changed and was kept");
        Store.Entry current = j.source.stat(before.path());
        if (current.size() != before.size() || current.modified() != before.modified())
          throw new IOException("Copy verified; source changed and was kept");
        j.checkpoint();
        j.source.delete(before.path(), false);
      }
      j.completed.incrementAndGet();
    } finally {
      if (temp != null)
        try {
          j.destination.delete(temp, false);
        } catch (Exception cleanup) {
          j.errors.add("A partial file needs cleanup: " + temp);
        }
    }
  }
  private static byte[] hash(Job j, Store store, String path) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (InputStream in = store.read(path)) {
      byte[] buffer = new byte[256 * 1024];
      int n;
      while (true) {
        j.checkpoint();
        long start = System.nanoTime();
        n = in.read(buffer);
        if (n < 0)
          break;
        if (n == 0)
          continue;
        long intentionalWait = j.throttle(n);
        hash.update(buffer, 0, n);
        j.ioBytes.addAndGet(n);
        j.latency(start + intentionalWait);
      }
    }
    return hash.digest();
  }
  private static String unique(Store store, String folder, String name) throws IOException {
    int dot = name.lastIndexOf('.');
    String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
    for (int i = 1; i <= 10000; i++) {
      String n = Store.name(stem + " (" + i + ")" + ext);
      if (!store.exists(folder, n))
        return n;
    }
    throw new IOException("Too many name conflicts");
  }
  public static String safeMessage(Throwable e) {
    String s = e.getMessage();
    return s == null ? e.getClass().getSimpleName() : s;
  }
}
