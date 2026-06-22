package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.lib.natives.NativeProcess;
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
    private NativeProcess process = null;
    private Thread monitor = null;
    private Thread errPump = null;
    private volatile boolean running = false;
    private volatile int exitCode = -1;
    private Runnable onUnexpectedExit = null;
    private volatile LineListener lineListener = null;
    /** Serialises line delivery across the stdout/stderr pumps. */
    private final Object listenerLock = new Object();
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
     * logging) -- used for child event streams (e.g. bridgedhcp JSON lines).
     */
    public void setLineListener(@Nullable LineListener listener) {
        lineListener = listener;
    }

    public synchronized boolean start(@NonNull List<String> args) {
        stop();
        Log.i(logTag, fmt("Starting: %s", String.join(" ", args)));
        appendLog(fmt("=== starting: %s ===", String.join(" ", args)));
        NativeProcess proc;
        try {
            exitCode = -1;
            proc = new NativeProcess.Builder(args.toArray(new String[0])).start();
        } catch (Exception e) {
            Log.e(logTag, "Failed to start process", e);
            process = null;
            running = false;
            return false;
        }
        process = proc;
        running = true;
        // NativeProcess keeps stdout/stderr on separate pipes (no
        // redirectErrorStream); pump both into the same log/listener so the
        // merged-output behaviour callers rely on is preserved.
        var ep = new Thread(() -> pump(proc.getErrorStream()), fmt("err-%s", tag));
        ep.setDaemon(true);
        errPump = ep;
        monitor = new Thread(() -> monitorThread(proc, ep), fmt("mon-%s", tag));
        monitor.setDaemon(true);
        ep.start();
        monitor.start();
        return true;
    }

    private void monitorThread(@NonNull NativeProcess proc, @NonNull Thread errThread) {
        try {
            // stdout drains until the child closes it (i.e. exits / is killed)
            pump(proc.getInputStream());
            // flush any tail of stderr before reporting the exit
            errThread.join();
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.d(logTag, "Process monitor interrupted");
        } catch (Exception e) {
            Log.e(logTag, "Process monitor failed", e);
        } finally {
            running = false;
            proc.close();
        }
    }

    /** Reads one output stream line-by-line until EOF. */
    private void pump(@NonNull InputStream in) {
        try (var reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null)
                handleLine(line);
        } catch (Exception e) {
            // Expected when stop() kills the child and the pipe closes mid-read.
            Log.d(logTag, fmt("output stream closed: %s", e.getMessage()));
        }
    }

    /** Logs, buffers and dispatches one line; serialised so listeners see one
     *  line at a time (as they did when stderr was merged into stdout). */
    private void handleLine(@NonNull String line) {
        Log.d(logTag, line);
        appendLog(line);
        var listener = lineListener;
        if (listener == null) return;
        synchronized (listenerLock) {
            try {
                listener.onLine(line);
            } catch (Exception e) {
                Log.w(logTag, "Line listener failed", e);
            }
        }
    }

    public synchronized void stop() {
        // Diagnostic: a live helper being stopped is either a legitimate
        // teardown or the still-unexplained SIGTERM source -- log who asked so
        // the caller is identifiable in logcat. Restarts go through start()
        // only after the process already died, so this stays quiet for those.
        if (running)
            Log.w(logTag, fmt("stop() on live process; caller:\n%s",
                Log.getStackTraceString(new Throwable())));
        running = false;
        if (monitor != null) {
            monitor.interrupt();
            monitor = null;
        }
        if (errPump != null) {
            errPump.interrupt();
            errPump = null;
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
        proc.waitFor();
        proc.close();
    }

    /** Sends a signal (e.g. SIGHUP for config reload) to the running process. */
    public synchronized boolean signal(int signal) {
        var proc = process;
        if (proc == null || !proc.isAlive()) return false;
        int pid = proc.pid();
        if (pid <= 0) return false;
        return ProcessUtils.shellKillProcess(pid, signal);
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
