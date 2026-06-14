package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.lib.utils.ProcessUtils;

/**
 * Supervises one external helper daemon (dnsmasq, gvswitch, pbridge):
 * spawns it in the foreground, pumps its output to the log, tracks
 * liveness/exit code and stops it with SIGTERM then SIGKILL.
 */
public class ManagedProcess {
    public interface LineListener {
        void onLine(@NonNull String line);
    }

    private final String tag;
    private final String logTag;
    private Process process = null;
    private Thread monitor = null;
    private volatile boolean running = false;
    private volatile int exitCode = -1;
    private Runnable onUnexpectedExit = null;
    private volatile LineListener lineListener = null;
    /** Recent merged stdout+stderr lines, surfaced to the network info UI. */
    private static final int LOG_CAP = 1000;
    private final ArrayDeque<String> logBuffer = new ArrayDeque<>();

    public ManagedProcess(@NonNull String name, @NonNull String instance) {
        this.tag = fmt("%s-%s", name, instance);
        this.logTag = tag.length() > 23 ? tag.substring(0, 23) : tag;
    }

    /** Called from the monitor thread when the process dies without stop(). */
    public void setOnUnexpectedExit(@Nullable Runnable callback) {
        onUnexpectedExit = callback;
    }

    /**
     * Receives every output line from the monitor thread (in addition to
     * logging) — used for child event streams (e.g. bridgedhcp JSON lines).
     */
    public void setLineListener(@Nullable LineListener listener) {
        lineListener = listener;
    }

    public synchronized boolean start(@NonNull List<String> args) {
        stop();
        Log.i(logTag, fmt("Starting: %s", String.join(" ", args)));
        appendLog(fmt("=== starting: %s ===", String.join(" ", args)));
        try {
            var pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            exitCode = -1;
            process = pb.start();
        } catch (Exception e) {
            Log.e(logTag, "Failed to start process", e);
            process = null;
            running = false;
            return false;
        }
        running = true;
        monitor = new Thread(this::monitorThread, fmt("mon-%s", tag));
        monitor.setDaemon(true);
        monitor.start();
        return true;
    }

    private void monitorThread() {
        var proc = process;
        if (proc == null) return;
        try {
            try (var reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(logTag, line);
                    appendLog(line);
                    var listener = lineListener;
                    if (listener != null) {
                        try {
                            listener.onLine(line);
                        } catch (Exception e) {
                            Log.w(logTag, "Line listener failed", e);
                        }
                    }
                }
            }
            int code = proc.waitFor();
            exitCode = code;
            boolean unexpected = running;
            running = false;
            appendLog(fmt("=== process exited (code %d) ===", code));
            if (unexpected) {
                Log.w(logTag, fmt("Process exited unexpectedly (code %d)", code));
                var cb = onUnexpectedExit;
                if (cb != null) cb.run();
            } else {
                Log.i(logTag, fmt("Process exited (code %d)", code));
            }
        } catch (InterruptedException | InterruptedIOException e) {
            Thread.currentThread().interrupt();
            Log.d(logTag, "Process monitor interrupted");
        } catch (Exception e) {
            Log.e(logTag, "Process monitor failed", e);
        } finally {
            running = false;
        }
    }

    public synchronized void stop() {
        running = false;
        if (monitor != null) {
            monitor.interrupt();
            monitor = null;
        }
        var proc = process;
        process = null;
        if (proc == null) return;
        Log.i(logTag, "Stopping process");
        proc.destroy();
        try {
            proc.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        proc.destroyForcibly();
        try {
            proc.waitFor();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sends a signal (e.g. SIGHUP for config reload) to the running process. */
    public synchronized boolean signal(int signal) {
        var proc = process;
        if (proc == null || !proc.isAlive()) return false;
        int pid = pidOf(proc);
        if (pid <= 0) return false;
        return ProcessUtils.shellKillProcess(pid, signal);
    }

    /** java.lang.Process.pid() is not in the Android compile SDK; reflect. */
    private static int pidOf(@NonNull Process proc) {
        try {
            var method = Process.class.getMethod("pid");
            return (int) (long) (Long) method.invoke(proc);
        } catch (Exception ignored) {
        }
        try {
            var field = proc.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return field.getInt(proc);
        } catch (Exception e) {
            Log.w("ManagedProcess", "Failed to determine process pid", e);
            return -1;
        }
    }

    private void appendLog(@NonNull String line) {
        synchronized (logBuffer) {
            while (logBuffer.size() >= LOG_CAP) logBuffer.removeFirst();
            logBuffer.addLast(line);
        }
    }

    /** Snapshot of the last {@value #LOG_CAP} captured output lines. */
    @NonNull
    public List<String> getLog() {
        synchronized (logBuffer) {
            return new ArrayList<>(logBuffer);
        }
    }

    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    public int getExitCode() {
        return exitCode;
    }
}
