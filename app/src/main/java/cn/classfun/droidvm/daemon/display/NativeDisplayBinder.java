// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.display;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
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
import cn.classfun.droidvm.lib.store.vm.VMState;

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

    /** How long one lookup keeps looking, and how often it looks. */
    private static final long WAIT_TOTAL_MS = 5000;
    private static final long WAIT_POLL_MS = 200;

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
                return doWaitForDisplayBinder(ctx, serviceName);
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
        Log.i(TAG, fmt("broadcast native-display binder (nonce=%s)", nonce));
    }

    private static IBinder smCall(@NonNull String method, @NonNull String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod(method, String.class);
            return (IBinder) m.invoke(null, name);
        } catch (Exception e) {
            Log.w(TAG, fmt("%s reflection failed: %s", method, e.getMessage()));
            return null;
        }
    }

    /**
     * Look for [name] until it turns up or [WAIT_TOTAL_MS] passes.
     *
     * Polls {@code checkService} rather than calling {@code waitForService}, which blocks until
     * the service appears and cannot be cancelled. A caller-side timeout around it does not stop
     * it: the thread carrying it stays blocked for the life of the process, and while it waits,
     * servicemanager's client logs a line a second, from every thread. For a VM that has stopped
     * -- the display console left open after the VM exits, retrying -- the service never appears
     * at all, so every attempt leaked another permanently-waiting, permanently-logging thread.
     * Measured: 315884 log lines and 77 MB of daemon.log in four minutes, ending with the daemon
     * dying and taking a different, running VM's crosvm down with it.
     */
    private static IBinder pollForService(@NonNull String name) {
        long deadline = System.nanoTime() + WAIT_TOTAL_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(WAIT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            var binder = smCall("checkService", name);
            if (binder != null) return binder;
        }
        return null;
    }

    /**
     * Whether the VM behind [serviceName] is in a state where crosvm could still register it.
     *
     * A stopped VM will never register, so waiting for it is time the caller spends learning
     * nothing -- and the caller is a console that retries. Unknown names are not ours to judge,
     * so they wait as before.
     */
    private static boolean vmCouldRegister(@NonNull ServerContext ctx, @NonNull String serviceName) {
        // NativeDisplay owns both halves of the name -- the VM's channel root and the screen id
        // appended to it -- so the reverse mapping lives there too rather than as a prefix strip
        // written out again here, which is how it would silently stop matching.
        var vmId = NativeDisplay.vmIdFromServiceName(serviceName);
        if (vmId.isEmpty()) return true;
        var inst = ctx.getVMs().findById(vmId);
        if (inst == null) return true;
        var state = inst.getState();
        return state == VMState.RUNNING || state == VMState.STARTING || state == VMState.REBOOTING;
    }

    private static IBinder doWaitForDisplayBinder(@NonNull ServerContext ctx,
                                                  @NonNull String serviceName) {
        var direct = smCall("checkService", serviceName);
        if (direct != null) return direct;
        if (!vmCouldRegister(ctx, serviceName)) {
            Log.i(TAG, fmt("'%s': VM not running, nothing to wait for", serviceName));
            return null;
        }
        Log.i(TAG, fmt("waitForDisplayBinder('%s'): not registered yet, looking for %d ms",
            serviceName, WAIT_TOTAL_MS));
        var waited = pollForService(serviceName);
        if (waited != null) {
            Log.i(TAG, "OK: got display binder");
            return waited;
        }
        Log.w(TAG, fmt("'%s' not found - is crosvm running with "
            + "--android-display-service %s?", serviceName, serviceName));
        return null;
    }
}
