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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.daemon.display.DaemonSystemContext;
import cn.classfun.droidvm.daemon.server.Server;
import cn.classfun.droidvm.lib.natives.UnixHelper;
import cn.classfun.droidvm.lib.utils.FileUtils;
import cn.classfun.droidvm.lib.utils.ProcessUtils;

public final class Daemon {
    public static final String TAG = "Daemon";
    private static final String RUN_DIR = pathJoin(DATA_DIR, "run");
    private static final String LOCK_FILE = pathJoin(RUN_DIR, "droidvmd.lock");
    private static final AtomicBoolean cleaned = new AtomicBoolean(false);
    public static String daemonHash = null;

    // Held for the whole process lifetime so the advisory lock stays taken; the
    // kernel drops it automatically when this process dies (including SIGKILL),
    // so a stale lock can never block the next daemon.
    @SuppressWarnings("FieldCanBeLocal")
    private static FileChannel lockChannel;
    @SuppressWarnings("FieldCanBeLocal")
    private static FileLock daemonLock;

    /**
     * Single-instance gate. Tries to take an exclusive lock on
     * {@code droidvmd.lock}. Returns true when held by this process. When the
     * lock is already taken by a live daemon: with {@code force}, that daemon
     * is terminated (SIGINT, then SIGKILL) and the lock is taken over;
     * without it, this returns false so the caller exits instead of running a
     * second daemon.
     */
    private static boolean acquireLock(boolean force) {
        var runDir = new File(RUN_DIR);
        if (!runDir.exists() && !runDir.mkdirs())
            Log.w(TAG, fmt("Failed to create run directory: %s", runDir));
        try {
            lockChannel = FileChannel.open(Paths.get(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException e) {
            Log.e(TAG, fmt("Failed to open lock file: %s", LOCK_FILE), e);
            return false;
        }
        if (tryLock()) {
            writeLockOwner();
            return true;
        }
        int holder = readLockOwner();
        if (!force) {
            Log.e(TAG, fmt(
                "Another daemon (pid=%d) is already running; exiting "
                    + "(start with --force to take over)", holder));
            return false;
        }
        Log.i(TAG, fmt("--force: terminating existing daemon (pid=%d)", holder));
        if (holder > 1) ProcessUtils.shellKillProcess(holder, ProcessUtils.SIGINT);
        if (waitForLock(10_000)) {
            writeLockOwner();
            return true;
        }
        if (holder > 1) {
            Log.w(TAG, fmt("Existing daemon (pid=%d) did not exit; sending SIGKILL", holder));
            ProcessUtils.shellKillProcess(holder, ProcessUtils.SIGKILL);
        }
        if (waitForLock(3_000)) {
            writeLockOwner();
            return true;
        }
        Log.e(TAG, "Failed to take over the daemon lock");
        return false;
    }

    private static boolean tryLock() {
        try {
            daemonLock = lockChannel.tryLock();
            return daemonLock != null;
        } catch (Exception e) {
            // OverlappingFileLockException can't happen (single attempt); any
            // other failure is treated as "not acquired".
            Log.w(TAG, "tryLock failed", e);
            return false;
        }
    }

    private static boolean waitForLock(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (tryLock()) return true;
            try {
                //noinspection BusyWait
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /** Records this daemon's pid in the lock file so a later --force can find it. */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void writeLockOwner() {
        try {
            lockChannel.truncate(0);
            lockChannel.position(0);
            lockChannel.write(ByteBuffer.wrap(String.valueOf(Process.myPid()).getBytes()));
            lockChannel.force(false);
        } catch (IOException e) {
            Log.w(TAG, "Failed to write lock owner pid", e);
        }
    }

    /** Pid of the current lock holder: from the lock file, else the pid file. */
    private static int readLockOwner() {
        for (var path : new String[]{LOCK_FILE, getPidFile()}) {
            try {
                var s = FileUtils.readFile(path).trim();
                if (!s.isEmpty()) return Integer.parseInt(s);
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

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
        boolean force = Arrays.asList(args).contains("--force");
        System.out.print("Starting DroidVM Daemon...\n");
        UnixHelper.load();
        // Build a system Context on the main thread (it prepares the main looper) so the daemon can
        // broadcast the native-display binder to the UI. Must happen before server.run() blocks here.
        DaemonSystemContext.init();
        System.out.printf("Current pid: %d\n", Process.myPid());
        Log.d(TAG, "DroidVM Daemon is starting...");
        if (!acquireLock(force)) {
            System.out.print("Another DroidVM Daemon is already running.\n");
            System.exit(1);
        }
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
