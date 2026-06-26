package cn.classfun.droidvm.daemon.display;

import static cn.classfun.droidvm.BuildConfig.APPLICATION_ID;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;

/**
 * Daemon-hosted native-display broker. The daemon already runs as root, so it does both jobs the
 * UI used to reach through a separate libsu RootService:
 * <ul>
 *   <li>{@link INativeDisplayRootService#waitForDisplayBinder(String)} - look up the per-VM
 *       ICrosvmAndroidDisplayService binder crosvm registers via
 *       {@code --android-display-service <serviceName>} (an untrusted_app can't do this lookup).</li>
 *   <li>{@link INativeDisplayRootService#writeInput(String, int, byte[])} - write evdev straight to
 *       the crosvm input socket the daemon owns (no extra socket hop), by looking up the VM.</li>
 * </ul>
 *
 * The binder can't ride the daemon's TCP/JSON-RPC channel, so it is broadcast to the UI through
 * system_server (see {@link #attach}); the {@code display_attach} IPC command triggers that.
 */
public final class NativeDisplayBinder {
    private static final String TAG = "NativeDisplayBinder";

    private static INativeDisplayRootService.Stub binder;

    private NativeDisplayBinder() {
    }

    /**
     * Builds the broker binder once and broadcasts it to the UI Activity that requested it with
     * [nonce]. Called from the {@code display_attach} IPC handler (a daemon worker thread).
     */
    public static synchronized void attach(@NonNull ServerContext ctx, @NonNull String nonce) {
        if (binder == null) binder = createBinder(ctx);
        broadcast(binder, nonce);
    }

    private static INativeDisplayRootService.Stub createBinder(@NonNull ServerContext ctx) {
        return new INativeDisplayRootService.Stub() {
            @Override
            public IBinder waitForDisplayBinder(String serviceName) {
                return doWaitForDisplayBinder(serviceName);
            }

            @Override
            public boolean writeInput(String vmId, int channel, byte[] data) {
                if (vmId == null || data == null || data.length == 0) return false;
                var inst = ctx.getVMs().findById(vmId);
                if (inst == null) return false;
                return inst.writeNativeInput(channel, data);
            }
        };
    }

    /** Broadcasts [binder] to our package with [nonce] nested in a Bundle (kept off the top-level
     * extras so AMS doesn't strip the live IBinder). */
    private static void broadcast(@NonNull IBinder binder, @NonNull String nonce) {
        var context = DaemonSystemContext.get();
        if (context == null) {
            Log.e(TAG, "no system context; cannot broadcast display binder");
            return;
        }
        var bundle = new Bundle();
        bundle.putBinder(NativeDisplay.EXTRA_BINDER, binder);
        bundle.putString(NativeDisplay.EXTRA_NONCE, nonce);
        var intent = new Intent(NativeDisplay.BINDER_BROADCAST_ACTION);
        intent.setPackage(APPLICATION_ID);
        intent.putExtra(NativeDisplay.EXTRA_BUNDLE, bundle);
        context.sendBroadcast(intent);
        Log.i(TAG, "broadcast native-display binder (nonce=" + nonce + ")");
    }

    private static IBinder smCall(@NonNull String method, @NonNull String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod(method, String.class);
            return (IBinder) m.invoke(null, name);
        } catch (Exception e) {
            Log.w(TAG, method + " reflection failed: " + e.getMessage());
            return null;
        }
    }

    private static IBinder waitForServiceWithTimeout(@NonNull String name, long timeoutMs) {
        var holder = new IBinder[1];
        var t = new Thread(() -> holder[0] = smCall("waitForService", name), "WaitSvc-" + name);
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }
        return holder[0];
    }

    private static IBinder doWaitForDisplayBinder(@NonNull String serviceName) {
        Log.i(TAG, "waitForDisplayBinder('" + serviceName + "')");
        var direct = smCall("checkService", serviceName);
        if (direct != null) {
            Log.i(TAG, "OK: got display binder directly from ServiceManager");
            return direct;
        }
        Log.i(TAG, "Not found, waiting up to 5s...");
        var waited = waitForServiceWithTimeout(serviceName, 5000L);
        if (waited != null) {
            Log.i(TAG, "OK: got display binder via waitForService");
            return waited;
        }
        Log.e(TAG, "'" + serviceName + "' not found - is crosvm running with "
            + "--android-display-service " + serviceName + "?");
        return null;
    }
}
