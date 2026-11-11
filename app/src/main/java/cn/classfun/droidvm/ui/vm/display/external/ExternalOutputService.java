// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.external;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Presentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.main.MainActivity;

/**
 * Holds a VM screen's output on an external display, apart from any activity: the picture stays
 * there while the app is in the background, on another of its pages, or closed, until the user
 * brings it back or the display goes away.
 *
 * <p>The window is a {@link Presentation}, as the VNC projection page had, but on a window context
 * of the service's rather than on an activity, whose window token would take the presentation
 * down with the activity. The service is foreground so that the process holding the window -- and
 * the decoder, for VNC -- is not frozen or killed under it.</p>
 *
 * <p>Stage 0 of the console redesign (meta repo todo/console-ui.md): the window and its lifetime,
 * with a running clock in place of the picture to see that it is alive and drawing.</p>
 */
public final class ExternalOutputService extends Service {
    private static final String TAG = "ExternalOutput";
    private static final String CHANNEL_ID = "external_output";
    private static final int NOTIF_ID = 0x4f55;
    public static final String EXTRA_DISPLAY_ID = "display_id";
    public static final String ACTION_STOP = "cn.classfun.droidvm.action.EXTERNAL_OUTPUT_STOP";
    /** WindowManager.LayoutParams.TYPE_PRESENTATION, which the SDK hides (FIRST_SYSTEM_WINDOW + 37). */
    private static final int TYPE_PRESENTATION = 2037;

    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable
    private Presentation presentation;
    @Nullable
    private TextView clock;
    private long shownAt;
    private int ticks;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            var view = clock;
            if (view == null) return;
            ticks++;
            view.setText(fmt("external output\n%d s\ntick %d",
                (SystemClock.elapsedRealtime() - shownAt) / 1000, ticks));
            view.setBackgroundColor(ticks % 2 == 0 ? Color.rgb(20, 40, 90) : Color.rgb(90, 30, 20));
            main.postDelayed(this, 500);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        ensureChannel();
        try {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground refused", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY);
        var manager = getSystemService(DisplayManager.class);
        var display = manager == null ? null : manager.getDisplay(displayId);
        if (display == null) {
            Log.w(TAG, fmt("no display %d", displayId));
            stopSelf();
            return START_NOT_STICKY;
        }
        show(display);
        return START_NOT_STICKY;
    }

    /** Puts the window on [display], replacing one shown before. */
    private void show(@NonNull Display display) {
        hide();
        Context windowContext = createDisplayContext(display)
            .createWindowContext(TYPE_PRESENTATION, null);
        var window = new Presentation(windowContext, display) {
            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                var view = new TextView(getContext());
                view.setGravity(Gravity.CENTER);
                view.setTextColor(Color.WHITE);
                view.setTextSize(40);
                setContentView(view);
                clock = view;
            }
        };
        try {
            window.show();
        } catch (RuntimeException e) {
            Log.w(TAG, fmt("cannot show on display %d (%s)", display.getDisplayId(),
                display.getName()), e);
            clock = null;
            return;
        }
        presentation = window;
        shownAt = SystemClock.elapsedRealtime();
        ticks = 0;
        main.post(tick);
        Log.i(TAG, fmt("showing on display %d (%s)", display.getDisplayId(), display.getName()));
    }

    private void hide() {
        main.removeCallbacks(tick);
        clock = null;
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
    }

    @Override
    public void onDestroy() {
        hide();
        super.onDestroy();
    }

    @NonNull
    private android.app.Notification buildNotification() {
        var open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        var stop = PendingIntent.getService(this, 1,
            new Intent(this, ExternalOutputService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle(getString(R.string.external_output_title))
            .setContentText(getString(R.string.external_output_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.external_output_stop), stop)
            .build();
    }

    private void ensureChannel() {
        var nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        var channel = new NotificationChannel(CHANNEL_ID,
            getString(R.string.notif_channel_external_output),
            NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
