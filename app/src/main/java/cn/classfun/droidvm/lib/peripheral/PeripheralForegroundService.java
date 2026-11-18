// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.peripheral;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.LocationPermission;
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
 * <p>Nothing here names a kind of peripheral: the mask arrives in {@link #EXTRA_TYPES}, worked out
 * by the daemon from the devices its running VMs really attach.</p>
 *
 * <p>The daemon raises it, because only it knows when a VM starts (one can be started over IPC
 * with no UI open) and only a root caller may start a foreground service while the app is in the
 * background: {@code ActiveServices} exempts uid 0, and lets it address this non-exported
 * component. The native daemon does it as root through the shell, which passes a null caller --
 * the one shape of the call a process outside ActivityManager can make
 * (daemon/services/peripheral_foreground.cpp):</p>
 * <pre>
 * am start-foreground-service --user &lt;user&gt; -n &lt;applicationId&gt;/{@value #SERVICE_CLASS} --ei types &lt;mask&gt; [--ei gnss_sources &lt;bits&gt;]
 * am stopservice --user &lt;user&gt; -n &lt;applicationId&gt;/{@value #SERVICE_CLASS}
 * </pre>
 * <p>A start {@code am} reports as refused is not remembered there: the daemon asks again, after
 * a short delay and on the next VM state change, rather than believing the mask is up.</p>
 */
public final class PeripheralForegroundService extends Service {
    private static final String TAG = "PeripheralFgs";
    private static final String CHANNEL_ID = "peripheral_foreground";
    private static final int NOTIF_ID = 0x45_00_00_01;
    /**
     * The service types to carry ({@code ServiceInfo.FOREGROUND_SERVICE_TYPE_*}), the union over
     * every running VM; 0 stops the service. MUST match the daemon's {@code --ei types}.
     */
    public static final String EXTRA_TYPES = "types";

    /**
     * The class half of the component the daemon names. MUST match the daemon's
     * {@code peripheral_foreground_service}; the package half is the build's application id,
     * which the daemon is built with ({@code DROIDVM_PACKAGE_NAME}).
     */
    public static final String SERVICE_CLASS =
        "cn.classfun.droidvm.lib.peripheral.PeripheralForegroundService";

    /**
     * Which of the phone's location sources the running VMs' GNSS receivers want
     * ({@code Gnss.SOURCE_*} bits, AUTO resolved by the daemon against this phone), for the
     * {@link GnssFeeder} this service runs while it carries the location type. Beside the mask
     * because it is decided by the same walk of the same VMs. MUST match the daemon's
     * {@code --ei gnss_sources}.
     */
    public static final String EXTRA_GNSS_SOURCES = "gnss_sources";

    /** The phone's receiver and location for the VMs' GNSS ports, while the type is carried. */
    @Nullable
    private GnssFeeder gnssFeeder;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Every way the service ends lets go of the phone's location. */
    @Override
    public void onDestroy() {
        setGnssSources(0);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        int typeMask = intent == null ? 0 : intent.getIntExtra(EXTRA_TYPES, 0);
        // Location is the one type a user can have refused while the VM still runs fine without
        // it -- its receiver just never gets a fix. Asking for it anyway would make
        // startForeground throw and take the camera's capability down with it, so it is dropped
        // here and the rest is raised.
        if ((typeMask & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
            && !LocationPermission.isGranted(this)) {
            Log.w(TAG, "location permission not granted: VM GNSS receivers stay without a fix");
            typeMask &= ~ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        if (typeMask == 0) {
            setGnssSources(0);
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
            setGnssSources(0);
            stopSelf();
            return START_NOT_STICKY;
        }
        // The feeder only once the service carries the location type: before that the app's uid
        // is not let near the phone's location.
        setGnssSources((typeMask & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
            && intent != null ? intent.getIntExtra(EXTRA_GNSS_SOURCES, 0) : 0);
        // Not sticky: the policy re-applies from the live VM states, so a restart by the system
        // with no VM running would raise a service nothing asked for.
        return START_NOT_STICKY;
    }

    /** Starts, re-points or stops the GNSS feeder; 0 stops it. */
    private void setGnssSources(int sources) {
        if (sources == 0) {
            if (gnssFeeder != null) gnssFeeder.close();
            gnssFeeder = null;
            return;
        }
        if (gnssFeeder == null) gnssFeeder = new GnssFeeder(this);
        gnssFeeder.apply(sources);
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
