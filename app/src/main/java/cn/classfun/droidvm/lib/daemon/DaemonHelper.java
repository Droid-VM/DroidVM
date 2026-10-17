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
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGINT;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.isPidExists;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcessFile;
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
import cn.classfun.droidvm.ui.main.settings.MainSettingsFragment;
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
     * Native configuration ownership, once requested or recorded, is permanent: a native daemon
     * started without it would be refused by the persistent ownership gate.
     */
    public boolean useConfigurationOwner() {
        var context = ctx.getContext();
        return context.getSharedPreferences("droidvm_prefs", 0).getBoolean(
            MainSettingsFragment.KEY_NATIVE_CONFIGURATION_OWNER, false)
            || cn.classfun.droidvm.lib.store.base.VmConfigOwnership.claimed(context.getFilesDir());
    }

    /**
     * Launches droidvmd; it creates run/ itself and the launcher daemonizes it. The daemon takes
     * a single-instance lock, so a normal launch is a no-op when one is already running. With
     * {@code force}, the new daemon terminates the running one and takes over -- used for restart.
     */
    public boolean startDaemon(boolean force) {
        var cmd = fmt(
            "%s %s --data-dir %s --apk %s%s%s",
            escapedString(getAssetBinaryPath("daemon")),
            escapedString(getAssetBinaryPath("droidvmd")),
            escapedString(DATA_DIR),
            escapedString(getDaemonZipPath()),
            force ? " --force" : "",
            useConfigurationOwner() ? " --configuration-owner" : ""
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

    public boolean stopDaemon() {
        Log.i(TAG, "Stopping daemon");
        return shellKillProcessFile(getPidFile(), SIGINT);
    }

    public boolean restartDaemon() {
        Log.i(TAG, "Restarting daemon");
        stopDaemon();
        while (isDaemonRunning())
            threadSleepOrKill(1000);
        return startDaemon();
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
