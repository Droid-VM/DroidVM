package cn.classfun.droidvm.lib.download;

import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.utils.StringUtils.formatDuration;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.download.DiskDownloadManager.Progress;
import cn.classfun.droidvm.ui.main.MainActivity;

/**
 * Foreground service that runs {@link DiskDownloadManager#runDownload} on a
 * background thread and renders one notification per download covering every
 * state — connecting, downloading (%/size/speed/ETA), retrying, failed, done —
 * in the same notification (no separate completion/failure notification).
 */
public final class DiskDownloadService extends Service {
    private static final String TAG = "DiskDownloadService";
    private static final String ACTION_START = "cn.classfun.droidvm.download.START";
    private static final String ACTION_CANCEL = "cn.classfun.droidvm.download.CANCEL";
    private static final String EXTRA_ID = "download_id";
    private static final String CHANNEL_ID = "disk_downloads_progress";
    private static final int NOTIF_BASE = 0x44_44_00_00;
    private static final long POLL_MS = 1000;
    private static final long SPEED_WINDOW_MS = 800;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    /** Ids with a live (ongoing) notification. */
    private final Set<Long> active = new LinkedHashSet<>();
    /** Ids already handed to the executor, so a repeat START doesn't double-run. */
    private final Set<Long> submitted = new HashSet<>();
    private final java.util.Map<Long, Tracker> trackers = new java.util.HashMap<>();
    private NotificationManager nm;
    private long foregroundId = -1;
    private boolean polling = false;

    /** Per-download moving-average speed estimate. */
    private static final class Tracker {
        long lastTime = 0;
        long lastBytes = 0;
        long speed = 0;
    }

    public static void start(@NonNull Context ctx, long id) {
        var i = new Intent(ctx, DiskDownloadService.class)
            .setAction(ACTION_START)
            .putExtra(EXTRA_ID, id);
        ctx.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            long id = intent.getLongExtra(EXTRA_ID, -1);
            var action = intent.getAction();
            if (ACTION_CANCEL.equals(action) && id >= 0) {
                DiskDownloadManager.cancel(id);
            } else if (ACTION_START.equals(action) && id >= 0) {
                active.add(id);
                if (submitted.add(id)) {
                    var appCtx = getApplicationContext();
                    executor.submit(() -> DiskDownloadManager.runDownload(appCtx, id));
                }
            }
        }
        if (active.isEmpty()) {
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }
        promoteForeground();
        startPolling();
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "Foreground service timed out; stopping");
        stopForegroundAndSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(pollTask);
        executor.shutdownNow();
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(pollTask);
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            tick();
            if (active.isEmpty()) {
                polling = false;
                stopForegroundAndSelf();
            } else {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };

    private void tick() {
        for (long id : new ArrayList<>(active)) {
            var p = DiskDownloadManager.query(id);
            if (p == null) {
                active.remove(id);
                if (nm != null) nm.cancel(notifId(id));
                continue;
            }
            switch (p.state) {
                case DiskDownloadManager.STATE_SUCCESS:
                    if (nm != null) nm.notify(notifId(id), buildDone(id));
                    active.remove(id);
                    break;
                case DiskDownloadManager.STATE_FAILED:
                    if (nm != null) nm.notify(notifId(id), buildFailed(id, p));
                    active.remove(id);
                    break;
                case DiskDownloadManager.STATE_CANCELLED:
                    if (nm != null) nm.cancel(notifId(id));
                    active.remove(id);
                    break;
                default:
                    if (nm != null) nm.notify(notifId(id), buildOngoing(id, p));
                    break;
            }
        }
        if (!active.isEmpty() && !active.contains(foregroundId))
            promoteForeground();
    }

    /** Anchors the foreground on a still-running download (its notification must
     * be ongoing). Stops the service if nothing is running. */
    private void promoteForeground() {
        if (active.contains(foregroundId)) return;
        for (long id : new ArrayList<>(active)) {
            var p = DiskDownloadManager.query(id);
            if (p == null) {
                active.remove(id);
                continue;
            }
            foregroundId = id;
            startForeground(notifId(id), buildOngoing(id, p),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            return;
        }
        stopForegroundAndSelf();
    }

    private void stopForegroundAndSelf() {
        handler.removeCallbacks(pollTask);
        polling = false;
        foregroundId = -1;
        // DETACH so finished (success/failed) notifications stay in the shade.
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private Notification buildOngoing(long id, @NonNull Progress p) {
        var b = baseBuilder(id)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_download_cancel), cancelIntent(this, id));
        if (p.state == DiskDownloadManager.STATE_PAUSED) {
            applyBar(b, p);
            b.setContentText(p.reason != null ? p.reason : getString(R.string.download_paused));
        } else if (p.state == DiskDownloadManager.STATE_RUNNING) {
            applyBar(b, p);
            b.setContentText(runningText(p, updateSpeed(id, p.downloaded)));
        } else { // connecting / no size yet
            b.setProgress(0, 0, true);
            b.setContentText(getString(R.string.notif_download_connecting));
        }
        return b.build();
    }

    private Notification buildDone(long id) {
        return baseBuilder(id)
            .setContentText(getString(R.string.notif_download_complete_title))
            .setAutoCancel(true)
            .build();
    }

    private Notification buildFailed(long id, @NonNull Progress p) {
        var reason = p.reason != null ? p.reason : "Unknown error";
        return baseBuilder(id)
            .setContentText(getString(R.string.download_failed, reason))
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText(getString(R.string.download_failed, reason)))
            .setAutoCancel(true)
            .build();
    }

    private NotificationCompat.Builder baseBuilder(long id) {
        var name = DiskDownloadManager.downloadName(id);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(name != null ? name : getString(R.string.app_name))
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(id));
    }

    private void applyBar(NotificationCompat.Builder b, Progress p) {
        if (p.total > 0) {
            b.setProgress(1000, (int) (p.downloaded * 1000 / p.total), false);
            b.setSubText(getString(R.string.download_percent, p.downloaded * 100f / p.total));
        } else {
            b.setProgress(0, 0, true);
        }
    }

    private String runningText(Progress p, long speed) {
        var text = p.total > 0
            ? getString(R.string.download_size_progress, formatSize(p.downloaded), formatSize(p.total))
            : getString(R.string.download_size_unknown, formatSize(p.downloaded));
        if (speed > 0) {
            text += " · " + getString(R.string.download_speed, formatSize(speed));
            if (p.total > 0)
                text += " · " + getString(R.string.download_eta,
                    formatDuration((p.total - p.downloaded) / speed));
        }
        return text;
    }

    private long updateSpeed(long id, long downloaded) {
        var t = trackers.computeIfAbsent(id, k -> new Tracker());
        long now = System.currentTimeMillis();
        if (t.lastTime == 0) {
            t.lastTime = now;
            t.lastBytes = downloaded;
            return t.speed;
        }
        long elapsed = now - t.lastTime;
        if (elapsed >= SPEED_WINDOW_MS) {
            t.speed = elapsed > 0 ? (downloaded - t.lastBytes) * 1000 / elapsed : 0;
            t.lastTime = now;
            t.lastBytes = downloaded;
        }
        return t.speed;
    }

    private PendingIntent contentIntent(long id) {
        // Reopen the import screen that started this download (bring its existing
        // instance to the front; create it if it's gone). Fall back to launching
        // the app if we don't know the source.
        var source = DiskDownloadManager.sourceActivity(id);
        Intent intent;
        if (source != null) {
            intent = new Intent().setClassName(this, source)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent == null) intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return PendingIntent.getActivity(this, (int) id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent cancelIntent(Context ctx, long id) {
        var intent = new Intent(ctx, DiskDownloadService.class)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_ID, id);
        return PendingIntent.getService(ctx, (int) id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void ensureChannel() {
        if (nm == null) return;
        var ch = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_downloads_progress),
            NotificationManager.IMPORTANCE_LOW
        );
        ch.setDescription(getString(R.string.notif_channel_downloads_progress_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private static int notifId(long id) {
        return NOTIF_BASE + (int) (id & 0xFFFF);
    }
}
