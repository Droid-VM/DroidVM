package cn.classfun.droidvm.daemon.network;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically reconciles every running network's helper processes (gvswitch /
 * pbridge / bridgedhcp): if a network is meant to be RUNNING but a helper has
 * died, the backend restarts it and re-initialises its state. Runs as a single
 * daemon thread polling every {@value #INTERVAL_MS}ms, the same way
 * DefaultRouterWatcher polls.
 */
final class NetworkWatchdog {
    private static final String TAG = "NetworkWatchdog";
    private static final long INTERVAL_MS = 5000;

    private final NetworkInstanceStore store;
    private ScheduledExecutorService exec = null;

    NetworkWatchdog(@NonNull NetworkInstanceStore store) {
        this.store = store;
    }

    synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "net-watchdog");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(
            this::tick, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
        Log.i(TAG, "Network watchdog started");
    }

    synchronized void stop() {
        if (exec == null) return;
        exec.shutdownNow();
        exec = null;
        Log.i(TAG, "Network watchdog stopped");
    }

    private void tick() {
        store.forEach((id, inst) -> safeReconcile(inst));
    }

    private void safeReconcile(@NonNull NetworkInstance inst) {
        try {
            inst.reconcile();
        } catch (Exception e) {
            Log.w(TAG, "Reconcile failed", e);
        }
    }
}
