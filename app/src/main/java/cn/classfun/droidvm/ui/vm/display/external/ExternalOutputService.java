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
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.main.MainActivity;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.display.NativeExternalOutput;

/**
 * Holds VM screens' pictures on external displays, apart from any activity: a picture stays there
 * while the app is in the background, on another of its pages, or closed, until the user brings it
 * back ({@link VmOutputRouter#bringBack}, or the notification) or the display goes away.
 *
 * <p>Each picture's window is a {@link Presentation}, as the VNC projection page had, but on a
 * window context of the service's rather than on an activity, whose window token would take the
 * presentation down with the activity. No overlay permission is needed for it. The service is
 * foreground so that the process holding the windows is not frozen or killed under them.</p>
 *
 * <p>Which picture is where is {@link VmOutputRouter}'s; this only keeps the windows it is told
 * to, and tells it of one that went away by itself.</p>
 */
public final class ExternalOutputService extends Service {
    private static final String TAG = "ExternalOutput";
    private static final String CHANNEL_ID = "external_output";
    private static final int NOTIF_ID = 0x4f55;
    /** WindowManager.LayoutParams.TYPE_PRESENTATION, which the SDK hides (FIRST_SYSTEM_WINDOW + 37). */
    private static final int TYPE_PRESENTATION = 2037;

    public static final String EXTRA_VM_ID = "vm_id";
    public static final String EXTRA_SCREEN = "screen";
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_DISPLAY_ID = "display_id";
    public static final String EXTRA_KEY = "key";
    /** Takes one screen's window ({@link #EXTRA_KEY}) down. */
    public static final String ACTION_REMOVE = "cn.classfun.droidvm.action.EXTERNAL_OUTPUT_REMOVE";
    /** The notification's: every picture back to the phone. */
    public static final String ACTION_BRING_BACK =
        "cn.classfun.droidvm.action.EXTERNAL_OUTPUT_BRING_BACK";

    /** One screen's window on an external display, and the picture in it. */
    private static final class Window extends Presentation {
        final String vmId;
        final String screenId;
        final int width;
        final int height;
        @Nullable
        NativeExternalOutput picture;

        Window(@NonNull Context windowContext, @NonNull Display display, @NonNull String vmId,
               @NonNull String screenId, int width, int height) {
            super(windowContext, display);
            this.vmId = vmId;
            this.screenId = screenId;
            this.width = width;
            this.height = height;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // The display stays awake while the picture is on it. While the phone is locked the
            // platform's keyguard presentation covers it (FLAG_SHOW_WHEN_LOCKED does not get a
            // window above that), and the picture is back once the phone is unlocked.
            var window = getWindow();
            if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            var root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);
            setContentView(root);
            picture = new NativeExternalOutput(getContext(), vmId, screenId, root, width, height);
            picture.start();
        }

        void close() {
            if (picture != null) {
                picture.stop();
                picture = null;
            }
            dismiss();
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Window> windows = new HashMap<>();
    private final DisplayManager.DisplayListener displayListener =
        new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                for (var key : new ArrayList<>(windows.keySet())) {
                    var window = windows.get(key);
                    if (window == null || window.getDisplay().getDisplayId() != displayId) continue;
                    Log.i(TAG, fmt("display %d went away with %s", displayId, key));
                    remove(key);
                    VmOutputRouter.get().onWindowGone(key);
                }
                stopIfEmpty();
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        var manager = getSystemService(DisplayManager.class);
        if (manager != null) manager.registerDisplayListener(displayListener, main);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_REMOVE.equals(action)) {
            remove(orEmpty(intent.getStringExtra(EXTRA_KEY)));
            stopIfEmpty();
            return START_NOT_STICKY;
        }
        if (ACTION_BRING_BACK.equals(action)) {
            for (var key : new ArrayList<>(windows.keySet()))
                VmOutputRouter.get().bringBack(this, key, true);
            remove(null);
            stopIfEmpty();
            return START_NOT_STICKY;
        }
        ensureChannel();
        try {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground refused", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            stopIfEmpty();
            return START_NOT_STICKY;
        }
        String vmId = orEmpty(intent.getStringExtra(EXTRA_VM_ID));
        String screenId = orEmpty(intent.getStringExtra(EXTRA_SCREEN));
        String key = VmOutputRouter.keyOf(vmId, screenId);
        var manager = getSystemService(DisplayManager.class);
        var display = manager == null ? null
            : manager.getDisplay(intent.getIntExtra(EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY));
        if (vmId.isEmpty() || display == null) {
            Log.w(TAG, fmt("nothing to show for %s: no such display", key));
            VmOutputRouter.get().onWindowGone(key);
            stopIfEmpty();
            return START_NOT_STICKY;
        }
        show(key, vmId, screenId, intent.getIntExtra(EXTRA_WIDTH, 1280),
            intent.getIntExtra(EXTRA_HEIGHT, 720), display);
        return START_NOT_STICKY;
    }

    /** [key]'s window on [display], replacing one it had. */
    private void show(@NonNull String key, @NonNull String vmId, @NonNull String screenId,
                      int width, int height, @NonNull Display display) {
        remove(key);
        Context windowContext = createDisplayContext(display)
            .createWindowContext(TYPE_PRESENTATION, null);
        var window = new Window(windowContext, display, vmId, screenId, width, height);
        try {
            window.show();
        } catch (RuntimeException e) {
            Log.w(TAG, fmt("cannot show %s on display %d (%s)", key, display.getDisplayId(),
                display.getName()), e);
            window.close();
            VmOutputRouter.get().onWindowGone(key);
            stopIfEmpty();
            return;
        }
        windows.put(key, window);
        Log.i(TAG, fmt("%s on display %d (%s)", key, display.getDisplayId(), display.getName()));
    }

    /** Takes [key]'s window down; null takes every one. */
    private void remove(@Nullable String key) {
        if (key == null) {
            for (var window : windows.values()) window.close();
            windows.clear();
            return;
        }
        var window = windows.remove(key);
        if (window != null) window.close();
    }

    private void stopIfEmpty() {
        if (!windows.isEmpty()) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        var manager = getSystemService(DisplayManager.class);
        if (manager != null) manager.unregisterDisplayListener(displayListener);
        for (var key : new ArrayList<>(windows.keySet())) {
            remove(key);
            VmOutputRouter.get().onWindowGone(key);
        }
        super.onDestroy();
    }

    @NonNull
    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    @NonNull
    private android.app.Notification buildNotification() {
        var open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        var back = PendingIntent.getService(this, 1,
            new Intent(this, ExternalOutputService.class).setAction(ACTION_BRING_BACK),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle(getString(R.string.external_output_title))
            .setContentText(getString(R.string.external_output_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.external_output_stop), back)
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
