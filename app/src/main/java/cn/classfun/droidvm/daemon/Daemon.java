package cn.classfun.droidvm.daemon;

import static cn.classfun.droidvm.BuildConfig.APPLICATION_ID;
import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.daemon.DaemonHelper.getPidFile;
import static cn.classfun.droidvm.lib.daemon.DaemonHelper.getPortFile;
import static cn.classfun.droidvm.lib.daemon.DaemonHelper.getTokenFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.daemon.server.Server;
import cn.classfun.droidvm.lib.natives.UnixHelper;
import cn.classfun.droidvm.lib.utils.FileUtils;

public final class Daemon {
    public static final String TAG = "Daemon";
    private static final String RUN_DIR = pathJoin(DATA_DIR, "run");
    private static final AtomicBoolean cleaned = new AtomicBoolean(false);
    public static String daemonHash = null;

    private static void writePidFile() {
        var runDir = new File(RUN_DIR);
        if (!runDir.exists() && !runDir.mkdirs())
            Log.w(TAG, fmt("Failed to create run directory: %s", runDir));
        var pidFile = new File(runDir, "droidvmd.pid");
        try (var writer = new FileWriter(pidFile)) {
            writer.write(String.valueOf(Process.myPid()));
            writer.flush();
            Log.i(TAG, fmt("PID file written: %s (pid=%d)", pidFile, Process.myPid()));
        } catch (IOException e) {
            Log.e(TAG, fmt("Failed to write PID file: %s", pidFile), e);
        }
    }

    private static void writeTokenFile() {
        var runDir = new File(RUN_DIR);
        if (!runDir.exists() && !runDir.mkdirs())
            Log.w(TAG, fmt("Failed to create run directory: %s", runDir));
        var tokenFile = new File(runDir, "droidvmd-token.txt");
        var token = UUID.randomUUID().toString().replace("-", "");
        try (var writer = new FileWriter(tokenFile)) {
            writer.write(token);
            writer.flush();
            Log.i(TAG, fmt("Token file written: %s", tokenFile));
        } catch (IOException e) {
            Log.e(TAG, fmt("Failed to write token file: %s", tokenFile), e);
        }
    }

    private static void cleanup(Server server) {
        if (!cleaned.compareAndSet(false, true)) return;
        Log.i(TAG, "Stopping all VMs and networks...");
        var ctx = server.getContext();
        ctx.getVMs().stopAll();
        ctx.getNetworks().stopAll();
        ctx.getNetworks().firewall.shutdown();
        ctx.getRouterWatcher().stop();
        FileUtils.deleteFile(getPidFile());
        FileUtils.deleteFile(getTokenFile());
        FileUtils.deleteFile(getPortFile());
        Log.i(TAG, "DroidVM Daemon shutdown complete");
    }

    @NonNull
    private static String getMyHash() {
        var pmResult = runList("pm", "path", APPLICATION_ID);
        if (!pmResult.isSuccess()) {
            pmResult.printLog(TAG);
            throw new RuntimeException(fmt("Failed to get package path"));
        }
        var pkgPath = pmResult.getOutString().trim();
        if (!pkgPath.startsWith("package:"))
            throw new RuntimeException(fmt("Unexpected pm output: %s", pkgPath));
        pkgPath = pkgPath.substring("package:".length()).trim();
        if (!new File(pkgPath).exists())
            throw new RuntimeException(fmt("Package path does not exist: %s", pkgPath));
        try {
            var hash = FileUtils.calcHashForFile(pkgPath, "SHA-256");
            if (hash.length() != 64)
                throw new RuntimeException(fmt("Unexpected hash length: %d", hash.length()));
            return hash;
        } catch (Exception e) {
            throw new RuntimeException(fmt("Failed to calculate hash for package: %s", pkgPath), e);
        }
    }

    public static void main(String... args) {
        System.out.print("Starting DroidVM Daemon...\n");
        UnixHelper.load();
        System.out.printf("Current pid: %d\n", Process.myPid());
        Log.d(TAG, "DroidVM Daemon is starting...");
        daemonHash = getMyHash();
        Log.i(TAG, fmt("DroidVM Daemon hash: %s", daemonHash));
        writePidFile();
        writeTokenFile();
        var server = new Server();
        UnixHelper.installSignalHandler("INT", sig -> {
            Log.i(TAG, fmt("Received signal %d (INT), shutting down...", sig));
            System.exit(128 + sig);
        });
        UnixHelper.installSignalHandler("TERM", sig -> {
            Log.i(TAG, fmt("Received signal %d (TERM), shutting down...", sig));
            System.exit(128 + sig);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            cleanup(server);
        }));
        server.run();
        cleanup(server);
    }
}
