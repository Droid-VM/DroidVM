// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.peripheral;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import cn.classfun.droidvm.BuildConfig;
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

    /** {@code UserHandle.PER_USER_RANGE}, to get a user id out of a uid without a hidden API. */
    private static final int PER_USER_RANGE = 100_000;

    /**
     * The package this service lives in. Deliberately the build's application id and never
     * {@code context.getPackageName()}: the daemon's Context is the <em>system</em> Context, whose
     * package is {@code android}, and an intent addressed from it names a component that does not
     * exist (defect D11 -- {@code cmp=android/...PeripheralForegroundService}).
     */
    private static final String SERVICE_PACKAGE = BuildConfig.APPLICATION_ID;

    /** The class half of the component, held once so the two accessors below cannot drift. */
    private static final String SERVICE_CLASS = PeripheralForegroundService.class.getName();

    /** The component to address the service by; see {@link #SERVICE_PACKAGE}. */
    @NonNull
    static ComponentName componentIn(@NonNull String packageName) {
        return new ComponentName(packageName, SERVICE_CLASS);
    }

    /**
     * The same component flattened, as the platform prints it: {@code package/class}. For the
     * logs, and for the tests -- a {@link ComponentName} is a stub off a device and cannot be
     * read back, while these are the two strings the whole of D11 was about.
     */
    @NonNull
    static String componentString(@NonNull String packageName) {
        return packageName + "/" + SERVICE_CLASS;
    }

    /**
     * Brings the service in line with {@code typeMask}: starts or re-types it when non-zero,
     * stops it when zero. Safe to call with the value it already has.
     *
     * <p>Called from the daemon, which is a bare root {@code app_process}. That costs two things,
     * both of which this method has to pay and neither of which {@code Context.startService} can.
     * <b>The component</b> is set explicitly in {@link #SERVICE_PACKAGE}, because
     * {@code new Intent(context, Class)} would take the package from the daemon Context, which is
     * {@code android}. <b>The caller</b> is passed as null through {@code IActivityManager}
     * directly, because {@code ActiveServices.startServiceLocked} rejects a caller whose
     * {@code IApplicationThread} has no process record -- and the daemon's ActivityThread, built
     * by {@code DaemonSystemContext} and never attached to ActivityManager, has none:
     * "Unable to find app for caller ... when starting service"
     * ({@code frameworks/base/services/core/java/com/android/server/am/ActiveServices.java:974}).
     * A null caller is exactly what {@code am start-foreground-service} passes, and it is the only
     * shape of the call that reaches the rest of the checks.</p>
     *
     * <p>Those checks then do exempt the daemon, which is the part the old javadoc got right:
     * with {@code callingUid} 0, {@code shouldAllowFgsWhileInUsePermission} returns
     * {@code REASON_SYSTEM_UID} for {@code ROOT_UID} ({@code ActiveServices.java:8773}) and
     * {@code verifyPackage} lets root name any package ({@code ActiveServices.java:9461}), so the
     * background-start restriction an app process would hit does not apply. What was never true
     * is that this worked as written: it never got as far as those checks.</p>
     *
     * <p>If the ActivityManager route is not reachable -- a real app process, where the hidden
     * interface is not visible -- the ordinary {@code Context} calls are used instead, still with
     * the explicit component. Nothing in the tree calls this from the app today.</p>
     *
     * <p>Returns whether the request was accepted, which is only as much as the caller can know
     * here: the start is asynchronous, so true means the platform took the start, not that
     * {@code startForeground} has run. False is a refusal that has already happened, and the
     * caller has to remember that it did -- otherwise it believes the mask is up and never asks
     * again. Whether the service then fails to carry its type is reported by
     * {@link #onStartCommand} stopping itself, which the caller finds out about the next time it
     * asks for a different mask.</p>
     */
    public static boolean apply(@NonNull Context context, int typeMask) {
        var intent = new Intent().setComponent(componentIn(SERVICE_PACKAGE));
        if (typeMask != 0) intent.putExtra(EXTRA_TYPES, typeMask);
        int userId = userIdOf(context);
        var direct = applyThroughActivityManager(intent, typeMask, userId);
        if (direct != null) return direct;
        try {
            if (typeMask == 0) {
                context.stopService(intent);
                return true;
            }
            context.startForegroundService(intent);
            return true;
        } catch (Exception e) {
            // Background-start restrictions, or a missing FOREGROUND_SERVICE_* permission. The VM
            // still runs; only the peripheral that wanted this is affected, and it will report its
            // own failure to open.
            Log.w(TAG, fmt("could not reach %s through the Context", componentString(
                SERVICE_PACKAGE)), e);
            return false;
        }
    }

    /**
     * Start or stop through {@code IActivityManager} with a null caller, the way the shell does.
     *
     * <p>Null means the request was not made at all -- the interface is not reachable from this
     * process -- so the caller can fall back. True and false are answers: the platform took the
     * request, or refused it. {@code startService} reports a refusal by return value rather than
     * by throwing, with the marker packages {@code !} (permission), {@code !!} (foreground not
     * allowed) and {@code ?} (background start), and null for "no service started".</p>
     */
    @Nullable
    private static Boolean applyThroughActivityManager(@NonNull Intent intent, int typeMask,
                                                       int userId) {
        Object am;
        Class<?> iam;
        Class<?> thread;
        try {
            am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            iam = Class.forName("android.app.IActivityManager");
            thread = Class.forName("android.app.IApplicationThread");
            if (am == null) return null;
        } catch (Throwable t) {
            // Not reachable here (an app process with hidden-API enforcement on, or a shape of
            // the framework this does not know). Say so once, at debug: the caller has a route.
            Log.d(TAG, fmt("ActivityManager not reachable directly: %s", t));
            return null;
        }
        try {
            if (typeMask == 0) {
                // stopService(IApplicationThread caller, Intent, String resolvedType, int userId)
                var stop = iam.getMethod("stopService", thread, Intent.class, String.class,
                    int.class);
                stop.invoke(am, null, intent, null, userId);
                return true;
            }
            // startService(IApplicationThread caller, Intent, String resolvedType,
            //              boolean requireForeground, String callingPackage,
            //              String callingFeatureId, int userId)
            var start = iam.getMethod("startService", thread, Intent.class, String.class,
                boolean.class, String.class, String.class, int.class);
            var result = start.invoke(am, null, intent, null, true, SERVICE_PACKAGE, null, userId);
            if (result == null) {
                Log.w(TAG, fmt("no service started for %s in user %d",
                    componentString(SERVICE_PACKAGE), userId));
                return false;
            }
            var name = (ComponentName) result;
            var pkg = name.getPackageName();
            if ("!".equals(pkg) || "!!".equals(pkg) || "?".equals(pkg)) {
                Log.w(TAG, fmt("ActivityManager refused %s: %s %s",
                    componentString(SERVICE_PACKAGE), pkg, name.getClassName()));
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, fmt("could not %s %s through ActivityManager",
                typeMask == 0 ? "stop" : "raise", componentString(SERVICE_PACKAGE)), t);
            return false;
        }
    }

    /**
     * The user the app is installed in, from its uid -- not the caller's user, which for the root
     * daemon is 0 whatever the app's is. Falls back to 0, the only user a daemon started at boot
     * can be talking about anyway.
     */
    private static int userIdOf(@NonNull Context context) {
        try {
            return context.getPackageManager().getApplicationInfo(SERVICE_PACKAGE, 0).uid
                / PER_USER_RANGE;
        } catch (Throwable t) {
            Log.w(TAG, fmt("could not resolve the user of %s; assuming 0", SERVICE_PACKAGE), t);
            return 0;
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
