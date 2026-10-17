// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.daemon;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.isPidExists;
import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleepOrKill;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.UIContext;

public final class DaemonHelper {
    private static final String TAG = "DaemonHelper";
    public static final String LOG_PATH = pathJoin(DATA_DIR, "cache", "daemon.log");
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final UIContext ctx;
    private Consumer<Boolean> onRefreshDaemonStatus = null;

    public DaemonHelper(@NonNull UIContext ctx) {
        this.ctx = ctx;
    }

    @NonNull
    public static String getPidFile() {
        return pathJoin(DATA_DIR, "run", "droidvmd.pid");
    }

    @NonNull
    public static String getTokenFile() {
        return pathJoin(DATA_DIR, "run", "droidvmd-token.txt");
    }

    @NonNull
    public static String getPortFile() {
        return pathJoin(DATA_DIR, "run", "droidvmd-port.txt");
    }

    public static int readPort() {
        var portStr = shellReadFile(getPortFile()).trim();
        if (portStr.isEmpty())
            throw new IllegalStateException("Port file is empty");
        var port = parseInt(portStr);
        if (port <= 0 || port > 65535)
            throw new NumberFormatException("Port file has invalid port");
        return port;
    }

    @NonNull
    public String getDaemonZipPath() {
        return ctx.getContext().getApplicationContext().getPackageCodePath();
    }

    public static int readPid() {
        try {
            var pid = parseInt(shellReadFile(getPidFile()).trim());
            if (isPidExists(pid)) {
                try {
                    var comm = shellReadFile(fmt("/proc/%d/comm", pid)).trim();
                    if (comm.equals("droidvmd")) return pid;
                    Log.i(TAG, fmt("proctitle mismatch: %s", comm));
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read proctitle", e);
                }
                Log.w(TAG, "pid file maybe stale");
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public static boolean isDaemonRunning() {
        return readPid() != -1;
    }

    @Nullable
    public static String readToken() {
        try {
            var token = shellReadFile(getTokenFile()).trim();
            if (token.length() == 32) return token;
            Log.w(TAG, "Token file has invalid length");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read token file", e);
            return null;
        }
    }

    public boolean startDaemon() {
        return startDaemon(false);
    }

    /**
     * Launches droidvmd; it creates run/ itself and the launcher daemonizes it. The daemon takes
     * a single-instance lock, so a normal launch is a no-op when one is already running. With
     * {@code force}, the new daemon terminates the running one and takes over -- used for restart.
     * The daemon always owns VM, network and disk configuration; the app has no other writer.
     */
    public boolean startDaemon(boolean force) {
        // Clearing app data or reinstalling leaves the old daemon running on a deleted run
        // directory, invisible to the new lock: kill it and the helpers it leaves behind.
        run(KILL_STALE).printLog("droidvmd-stale");
        var cmd = fmt(
            "%s %s --data-dir %s --apk %s%s --configuration-owner",
            escapedString(getAssetBinaryPath("daemon")),
            escapedString(getAssetBinaryPath("droidvmd")),
            escapedString(DATA_DIR),
            escapedString(getDaemonZipPath()),
            force ? " --force" : ""
        );
        Log.i(TAG, fmt("Starting daemon: %s", cmd));
        var result = run(cmd);
        if (!result.isSuccess()) {
            Log.e(TAG, "Failed to start daemon");
            result.printLog("droidvmd");
            return false;
        }
        DaemonConnection.getInstance().connect();
        return true;
    }

    /**
     * Stops every droidvmd, not only the one in the pid file: a reinstall leaves the previous
     * daemon running on a deleted data directory. Each gets SIGINT to tear its networks and VMs
     * down, SIGKILL after the grace period; network helpers left without a daemon are killed.
     */
    public boolean stopDaemon() {
        Log.i(TAG, "Stopping all daemons");
        var result = run(STOP_ALL);
        result.printLog("droidvmd-stop");
        return result.isSuccess();
    }

    private static final String STOP_ALL =
        "pids=$(pidof droidvmd); [ -n \"$pids\" ] && kill -INT $pids; i=0; "
        + "while [ $i -lt 300 ] && pidof droidvmd >/dev/null; do sleep 0.1; i=$((i+1)); done; "
        + "pids=$(pidof droidvmd); [ -n \"$pids\" ] && { echo \"killing $pids\"; kill -KILL $pids; sleep 0.5; }; "
        + "for p in $(pidof pbridge netbox bridgedhcp gvswitch); do "
        + "case \"$(readlink /proc/$p/exe)\" in *cn.classfun.droidvm*/bin/*) echo \"orphan $p\"; kill -KILL $p;; esac; done; "
        + "! pidof droidvmd >/dev/null";

    private static final String KILL_STALE =
        "for p in $(pidof droidvmd); do "
        + "case \"$(ls -l /proc/$p/fd 2>/dev/null)\" in *'run/droidvmd.lock (deleted)'*) echo \"stale $p\"; kill -KILL $p;; esac; done; "
        + "sleep 0.2; for p in $(pidof pbridge netbox bridgedhcp gvswitch); do "
        + "[ \"$(awk '/^PPid/{print $2}' /proc/$p/status)\" = 1 ] || continue; "
        + "case \"$(readlink /proc/$p/exe)\" in *cn.classfun.droidvm*/bin/*) echo \"orphan $p\"; kill -KILL $p;; esac; done; true";

    /** Networks running before a restart are started again, not only auto-up ones. */
    public boolean restartDaemon() {
        Log.i(TAG, "Restarting daemon");
        var running = runningNetworks();
        stopDaemon();
        if (!startDaemon()) return false;
        restartNetworks(running);
        return true;
    }

    @NonNull
    private static java.util.List<String> runningNetworks() {
        var ids = new java.util.ArrayList<String>();
        try {
            var rows = DaemonConnection.getInstance().request(
                new org.json.JSONObject().put("command", "network_list")).optJSONArray("data");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                var row = rows.getJSONObject(i);
                if (row.optBoolean("desired_running", false) || "running".equals(row.optString("state")))
                    ids.add(row.getString("id"));
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot read running networks before restart", e);
        }
        return ids;
    }

    private static void restartNetworks(@NonNull java.util.List<String> ids) {
        if (ids.isEmpty()) return;
        var connection = DaemonConnection.getInstance();
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                var rows = connection.request(new org.json.JSONObject().put("command", "network_list")).optJSONArray("data");
                for (int i = 0; rows != null && i < rows.length(); i++) {
                    var row = rows.getJSONObject(i);
                    if (!ids.contains(row.getString("id")) || row.optBoolean("desired_running", false)) continue;
                    var response = connection.request(new org.json.JSONObject()
                        .put("command", "network_start").put("network_id", row.getString("id")));
                    if (!response.optBoolean("success", false))
                        Log.w(TAG, fmt("Network %s did not restart: %s", row.optString("name"), response.optString("message")));
                }
                return;
            } catch (Exception e) {
                threadSleepOrKill(200); // Connection is still being established.
            }
        }
        Log.w(TAG, "Daemon unreachable; networks were not restarted");
    }


    public void asyncStartDaemon() {
        runOnPool(() -> {
            boolean ok = startDaemon();
            mainHandler.post(() -> {
                if (!ctx.isAlive()) return;
                var ctx = this.ctx.getContext();
                if (!ok) Toast.makeText(ctx, R.string.vm_daemon_error, LENGTH_LONG).show();
                asyncRefreshDaemonStatus();
            });
        });
    }

    public void asyncStopDaemon() {
        runOnPool(() -> {
            boolean ok = stopDaemon();
            mainHandler.post(() -> {
                if (!ctx.isAlive()) return;
                var ctx = this.ctx.getContext();
                if (ok) {
                    Toast.makeText(ctx,
                        R.string.settings_daemon_stopped,
                        LENGTH_SHORT
                    ).show();
                } else {
                    Toast.makeText(ctx,
                        R.string.vm_daemon_error,
                        LENGTH_LONG
                    ).show();
                }
                asyncRefreshDaemonStatus();
            });
        });
    }

    public void asyncRestartDaemon() {
        runOnPool(() -> {
            boolean ok = restartDaemon();
            mainHandler.post(() -> {
                if (!ctx.isAlive()) return;
                var ctx = this.ctx.getContext();
                if (ok) {
                    Toast.makeText(ctx, R.string.settings_daemon_running, LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, R.string.vm_daemon_error, LENGTH_LONG).show();
                }
                asyncRefreshDaemonStatus();
            });
        });
    }


    public void asyncRefreshDaemonStatus() {
        if (onRefreshDaemonStatus == null) return;
        runOnPool(() -> {
            boolean running;
            try {
                running = DaemonHelper.isDaemonRunning();
            } catch (Exception e) {
                running = false;
            }
            boolean finalRunning = running;
            mainHandler.post(() -> {
                if (!ctx.isAlive()) return;
                if (onRefreshDaemonStatus != null)
                    onRefreshDaemonStatus.accept(finalRunning);
            });
        });
    }

    public void setOnRefreshDaemonStatus(Consumer<Boolean> onRefreshDaemonStatus) {
        this.onRefreshDaemonStatus = onRefreshDaemonStatus;
    }
}
