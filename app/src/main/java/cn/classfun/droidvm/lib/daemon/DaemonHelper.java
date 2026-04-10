package cn.classfun.droidvm.lib.daemon;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
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
import cn.classfun.droidvm.daemon.Daemon;
import cn.classfun.droidvm.lib.ui.UIContext;

public final class DaemonHelper {
    private static final String TAG = "DaemonHelper";
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
            return isPidExists(pid) ? pid : -1;
        } catch (Exception ignored) {
            return -1;
        }
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
        var zipPath = getDaemonZipPath();
        Log.i(TAG, fmt("Starting daemon via app_process64 with %s", zipPath));
        run("mkdir -p %s", pathJoin(DATA_DIR, "run"));
        var cmd = fmt(
            "env CLASSPATH=%s %s %s / %s",
            escapedString(zipPath),
            escapedString(getAssetBinaryPath("daemon")),
            escapedString(findExecute("app_process64")),
            escapedString(Daemon.class.getName())
        );
        Log.i(TAG, fmt("Running command: %s", cmd));
        var result = run(cmd);
        if (result.isSuccess()) {
            Log.i(TAG, "Daemon started successfully");
            DaemonConnection.getInstance().connect();
            return true;
        } else {
            Log.e(TAG, "Failed to start daemon");
            result.printLog("droidvmd");
            return false;
        }
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
