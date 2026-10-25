package dev.gridline.files;

import android.app.*;
import android.content.*;
import android.os.*;
import dev.gridline.files.core.Transfer;

public final class TransferService extends Service {
  private static final String CHANNEL = "transfers";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean running;
  private PowerManager.WakeLock wake;
  private FilesApp app;
  private final Runnable ticker = new Runnable() {
    public void run() {
      if (running) {
        getSystemService(NotificationManager.class).notify(1, notification());
        handler.postDelayed(this, 1000);
      }
    }
  };
  @Override
  public void onCreate() {
    super.onCreate();
    app = (FilesApp) getApplication();
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW));
  }
  private Notification notification() {
    String text = "Preparing transfer";
    for (Transfer.Job j : app.jobs)
      if (j.state == Transfer.State.RUNNING || j.state == Transfer.State.PAUSED
          || j.state == Transfer.State.SCANNING) {
        text = j.state + " · " + j.completed + " / " + j.totalFiles + " files";
        break;
      }
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent cancel = PendingIntent.getService(this, 1,
        new Intent(this, TransferService.class).setAction("cancel"),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_files)
        .setContentTitle("SMBits")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(null, "Cancel transfers", cancel).build())
        .build();
  }
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && "cancel".equals(intent.getAction())) {
      for (Transfer.Job j : app.jobs) j.cancel();
      return START_NOT_STICKY;
    }
    startForeground(1, notification());
    if (!running) {
      running = true;
      wake = getSystemService(PowerManager.class)
                 .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SMBits:transfer");
      wake.acquire(6 * 60 * 60 * 1000L);
      handler.post(ticker);
      getSharedPreferences("state", 0).edit().putBoolean("interrupted", true).apply();
      app.operations.execute(() -> {
        while (true) {
          Transfer.Job next = null;
          for (Transfer.Job j : app.jobs)
            if (j.state == Transfer.State.QUEUED) {
              next = j;
              break;
            }
          if (next == null)
            break;
          if (next.cancel)
            next.state = Transfer.State.CANCELED;
          else {
            app.tuning.attach(next);
            new Transfer().run(next);
          }
        }
        handler.post(() -> {
          running = false;
          if (wake != null && wake.isHeld())
            wake.release();
          handler.removeCallbacks(ticker);
          getSharedPreferences("state", 0).edit().putBoolean("interrupted", false).apply();
          // A job can be queued between the worker's last check and this callback.
          if (app.busy())
            onStartCommand(new Intent(), 0, startId);
          else {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
          }
        });
      });
    }
    return START_NOT_STICKY;
  }
  @Override
  public void onTimeout(int startId, int fgsType) {
    for (Transfer.Job j : app.jobs) j.cancel();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }
  @Override
  public void onDestroy() {
    handler.removeCallbacks(ticker);
    if (wake != null && wake.isHeld())
      wake.release();
    for (Transfer.Job j : app.jobs)
      if (j.state == Transfer.State.RUNNING || j.state == Transfer.State.PAUSED)
        j.cancel();
    super.onDestroy();
  }
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
