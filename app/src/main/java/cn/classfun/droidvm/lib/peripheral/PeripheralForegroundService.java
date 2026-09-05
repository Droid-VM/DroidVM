// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.peripheral;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.main.MainActivity;

/**
 * Holds the app's uid in a foreground state while a running VM carries a peripheral that needs it.
 *
 * <p>It exists because of where crosvm lives. The VM is a child of the root daemon, which is an
 * {@code app_process} started through su and completely outside the Android lifecycle --
 * ActivityManager does not know it exists, so nothing it does can affect its uid's process state.
 * But the host APIs those peripherals reach are gated on exactly that: AppOps resolves a
 * foreground-only permission by asking whether the <em>uid</em> carries the matching
 * {@code PROCESS_CAPABILITY_FOREGROUND_*}, which only a process ActivityManager manages can
 * supply. crosvm runs setuid to the app's uid, so a foreground service in the app process is what
 * lets it through -- measured: an unmanaged setuid'd process gets frames precisely while some
 * other process of the same uid is foreground, and ERROR_CAMERA_DISABLED otherwise.</p>
 *
 * <p>Nothing here names a kind of peripheral. The type mask comes from
 * {@code PeripheralType.getForegroundServiceType}, so a device that starts needing this only has
 * to say so there.</p>
 */
public final class PeripheralForegroundService extends Service {
    private static final String TAG = "PeripheralFgs";
    private static final String CHANNEL_ID = "peripheral_foreground";
    private static final int NOTIF_ID = 0x45_00_00_01;
    private static final String EXTRA_TYPES = "types";

    /**
     * Brings the service in line with {@code typeMask}: starts or re-types it when non-zero,
     * stops it when zero. Safe to call with the value it already has.
     *
     * <p>Called from the daemon, which is uid 0: {@code ActiveServices} exempts a root caller by
     * app id, and the background-start check seeds itself from that same verdict, so this works
     * with no app process in the foreground and no UI open. An app-process caller would be
     * refused in exactly that case, which is why the decision does not live there.</p>
     */
    public static void apply(@NonNull Context context, int typeMask) {
        var intent = new Intent(context, PeripheralForegroundService.class);
        if (typeMask == 0) {
            context.stopService(intent);
            return;
        }
        intent.putExtra(EXTRA_TYPES, typeMask);
        try {
            context.startForegroundService(intent);
        } catch (Exception e) {
            // Background-start restrictions, or a missing FOREGROUND_SERVICE_* permission. The VM
            // still runs; only the peripheral that wanted this is affected, and it will report its
            // own failure to open.
            Log.w(TAG, "could not raise the peripheral foreground service", e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        int typeMask = intent == null ? 0 : intent.getIntExtra(EXTRA_TYPES, 0);
        if (typeMask == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureChannel();
        try {
            startForeground(NOTIF_ID, buildNotification(), typeMask);
        } catch (Exception e) {
            // startForeground with a typed service throws when the matching runtime permission is
            // not held -- the user declined CAMERA after the peripheral was added, say. Stopping
            // is the honest outcome: a service that cannot carry the type it was raised for grants
            // no capability, and leaving it up would show a notification that promises otherwise.
            Log.w(TAG, fmt("startForeground rejected for types 0x%s",
                Integer.toHexString(typeMask)), e);
            stopSelf();
            return START_NOT_STICKY;
        }
        // Not sticky: the policy re-applies from the live VM states, so a restart by the system
        // with no VM running would raise a service nothing asked for.
        return START_NOT_STICKY;
    }

    @NonNull
    private android.app.Notification buildNotification() {
        var open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.peripheral_fgs_title))
            .setContentText(getString(R.string.peripheral_fgs_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build();
    }

    private void ensureChannel() {
        var nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        var channel = new NotificationChannel(CHANNEL_ID,
            getString(R.string.notif_channel_peripheral_foreground),
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_peripheral_foreground_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
