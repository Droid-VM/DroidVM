package cn.classfun.droidvm.lib.natives;

import static java.lang.Thread.sleep;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({
    "BooleanMethodIsAlwaysInverted",
    "JavaReflectionMemberAccess",
    "UnusedReturnValue",
    "unused"
})
@SuppressLint("DiscouragedPrivateApi")
public final class NativeProcess extends Process implements Closeable {
    private static final String TAG = "NativeProcess";
    private final int pid;
    private final int stdinFd;
    private final int stdoutFd;
    private final int stderrFd;
    private final OutputStream stdinStream;
    private final InputStream stdoutStream;
    private final InputStream stderrStream;
    private volatile boolean finished = false;
    private int exitCode = -1;

    private static native int[] nativeForkExec(
        @NonNull String[] argv,
        @Nullable String[] envp,
        @Nullable String dir,
        @NonNull int[] preserveFds,
        @Nullable long[] rlimits
    );

    private static native int nativeWaitPid(int pid);

    private static native int nativeTryWaitPid(int pid);

    private static native void nativeKill(int pid, int signal);

    private NativeProcess(int pid, int stdinFd, int stdoutFd, int stderrFd) {
        this.pid = pid;
        this.stdinFd = stdinFd;
        this.stdoutFd = stdoutFd;
        this.stderrFd = stderrFd;
        this.stdinStream = fdToOutputStream(stdinFd);
        this.stdoutStream = fdToInputStream(stdoutFd);
        this.stderrStream = fdToInputStream(stderrFd);
    }

    public int pid() {
        return pid;
    }

    public int getStdinFd() {
        return stdinFd;
    }

    public int getStdoutFd() {
        return stdoutFd;
    }

    public int getStderrFd() {
        return stderrFd;
    }

    @NonNull
    @Override
    public OutputStream getOutputStream() {
        return stdinStream;
    }

    @NonNull
    @Override
    public InputStream getInputStream() {
        return stdoutStream;
    }

    @NonNull
    @Override
    public InputStream getErrorStream() {
        return stderrStream;
    }

    @Override
    public synchronized int waitFor() {
        if (!finished) {
            exitCode = nativeWaitPid(pid);
            finished = true;
        }
        return exitCode;
    }

    @Override
    @SuppressWarnings("BusyWait")
    public boolean waitFor(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isFinished()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 50));
        }
        return true;
    }

    @Override
    public int exitValue() {
        if (!isFinished())
            throw new IllegalThreadStateException("Process has not exited");
        return exitCode;
    }

    public boolean isFinished() {
        if (finished) return true;
        int ret = nativeTryWaitPid(pid);
        if (ret != Integer.MIN_VALUE) {
            synchronized (this) {
                exitCode = ret;
                finished = true;
            }
        }
        return finished;
    }

    @Override
    public boolean isAlive() {
        return !isFinished();
    }

    @Override
    public void destroy() {
        if (!isFinished())
            nativeKill(pid, 15);
    }

    @Override
    public NativeProcess destroyForcibly() {
        if (!isFinished())
            nativeKill(pid, 9);
        return this;
    }

    @Override
    public void close() {
        try {
            stdinStream.close();
        } catch (IOException ignored) {
        }
        try {
            stdoutStream.close();
        } catch (IOException ignored) {
        }
        try {
            stderrStream.close();
        } catch (IOException ignored) {
        }
    }

    @NonNull
    @Override
    public String toString() {
        return fmt("NativeProcess[pid=%d, finished=%b, exitCode=%d]", pid, finished, exitCode);
    }

    @NonNull
    private static OutputStream fdToOutputStream(int fd) {
        try {
            var fileDescriptor = new FileDescriptor();
            var field = FileDescriptor.class.getDeclaredField("descriptor");
            field.setAccessible(true);
            field.setInt(fileDescriptor, fd);
            return new FileOutputStream(fileDescriptor);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, fmt("Failed to wrap fd as OutputStream, fd=%d", fd), e);
            return OutputStream.nullOutputStream();
        }
    }

    @NonNull
    private static InputStream fdToInputStream(int fd) {
        try {
            var fileDescriptor = new FileDescriptor();
            var field = FileDescriptor.class.getDeclaredField("descriptor");
            field.setAccessible(true);
            field.setInt(fileDescriptor, fd);
            return new FileInputStream(fileDescriptor);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, fmt("Failed to wrap fd as InputStream, fd=%d", fd), e);
            return InputStream.nullInputStream();
        }
    }

    public static final int RLIMIT_CPU = 0;  /* Max CPU time in seconds. */
    public static final int RLIMIT_FSIZE = 1;  /* Max file size in bytes. */
    public static final int RLIMIT_DATA = 2;  /* Max data segment size in bytes. */
    public static final int RLIMIT_STACK = 3;  /* Max stack size in bytes. */
    public static final int RLIMIT_CORE = 4;  /* Max core file size in bytes. */
    public static final int RLIMIT_RSS = 5;  /* Max resident set size in bytes. */
    public static final int RLIMIT_NPROC = 6;  /* Max number of processes. */
    public static final int RLIMIT_NOFILE = 7;  /* Max number of open file descriptors. */
    public static final int RLIMIT_MEMLOCK = 8;  /* Max locked memory in bytes. */
    public static final int RLIMIT_AS = 9;  /* Max address space size in bytes. */
    public static final int RLIMIT_LOCKS = 10; /* Max file locks. */
    public static final int RLIMIT_SIGPENDING = 11; /* Max pending signals. */
    public static final int RLIMIT_MSGQUEUE = 12; /* Max bytes in POSIX message queues. */
    public static final int RLIMIT_NICE = 13; /* Max nice priority. */
    public static final int RLIMIT_RTPRIO = 14; /* Max real-time priority. */
    public static final int RLIMIT_RTTIME = 15; /* Max real-time CPU time in microseconds. */
    public static final long RLIM_INFINITY = -1L;

    public static final class Builder {
        private final String[] argv;
        private final List<String> envList = new ArrayList<>();
        private final List<Integer> preserveFdList = new ArrayList<>();
        private final List<long[]> rlimitList = new ArrayList<>();
        private String directory = null;
        private boolean inheritEnv = true;

        public Builder(@NonNull String... argv) {
            if (argv.length == 0)
                throw new IllegalArgumentException("argv must not be empty");
            this.argv = argv.clone();
        }

        @NonNull
        public Builder directory(@Nullable String dir) {
            this.directory = dir;
            return this;
        }

        @NonNull
        public Builder inheritEnvironment(boolean inherit) {
            this.inheritEnv = inherit;
            return this;
        }

        @NonNull
        public Builder environment(@NonNull String key, @NonNull String value) {
            envList.add(fmt("%s=%s", key, value));
            return this;
        }

        @NonNull
        public Builder environment(@NonNull Map<String, String> env) {
            for (var e : env.entrySet())
                environment(e.getKey(), e.getValue());
            return this;
        }

        @NonNull
        public Builder preserveFd(int fd) {
            preserveFdList.add(fd);
            return this;
        }

        @NonNull
        public Builder preserveFds(@NonNull int... fds) {
            for (int fd : fds) preserveFdList.add(fd);
            return this;
        }

        @NonNull
        public Builder rlimit(int resource, long softLimit, long hardLimit) {
            rlimitList.add(new long[]{resource, softLimit, hardLimit});
            return this;
        }

        @NonNull
        public Builder rlimit(int resource, long limit) {
            return rlimit(resource, limit, limit);
        }

        @NonNull
        public Builder maxOpenFiles(long softLimit, long hardLimit) {
            return rlimit(RLIMIT_NOFILE, softLimit, hardLimit);
        }

        @NonNull
        public Builder maxOpenFiles(long limit) {
            return rlimit(RLIMIT_NOFILE, limit, limit);
        }

        @NonNull
        public Builder maxAddressSpace(long softLimit, long hardLimit) {
            return rlimit(RLIMIT_AS, softLimit, hardLimit);
        }

        @NonNull
        public Builder maxAddressSpace(long limit) {
            return rlimit(RLIMIT_AS, limit, limit);
        }

        @NonNull
        public Builder maxFileSize(long softLimit, long hardLimit) {
            return rlimit(RLIMIT_FSIZE, softLimit, hardLimit);
        }

        @NonNull
        public Builder maxFileSize(long limit) {
            return rlimit(RLIMIT_FSIZE, limit, limit);
        }

        @NonNull
        public Builder maxProcesses(long softLimit, long hardLimit) {
            return rlimit(RLIMIT_NPROC, softLimit, hardLimit);
        }

        @NonNull
        public Builder maxProcesses(long limit) {
            return rlimit(RLIMIT_NPROC, limit, limit);
        }

        @NonNull
        public Builder maxStackSize(long softLimit, long hardLimit) {
            return rlimit(RLIMIT_STACK, softLimit, hardLimit);
        }

        @NonNull
        public Builder maxStackSize(long limit) {
            return rlimit(RLIMIT_STACK, limit, limit);
        }

        @NonNull
        public Builder maxCoreSize(long softLimit, long hardLimit) {
            return rlimit(RLIMIT_CORE, softLimit, hardLimit);
        }

        @NonNull
        public Builder maxCoreSize(long limit) {
            return rlimit(RLIMIT_CORE, limit, limit);
        }

        @NonNull
        public Builder maxCpuTime(long softLimit, long hardLimit) {
            return rlimit(RLIMIT_CPU, softLimit, hardLimit);
        }

        @NonNull
        public Builder maxCpuTime(long limit) {
            return rlimit(RLIMIT_CPU, limit, limit);
        }

        @NonNull
        public Builder maxLockedMemory(long softLimit, long hardLimit) {
            return rlimit(RLIMIT_MEMLOCK, softLimit, hardLimit);
        }

        @NonNull
        public Builder maxLockedMemory(long limit) {
            return rlimit(RLIMIT_MEMLOCK, limit, limit);
        }

        @NonNull
        public NativeProcess start() throws IOException {
            if (!UnixHelper.isLoaded())
                throw new IOException("Native library not loaded");
            String[] envp = null;
            if (inheritEnv) {
                var merged = new LinkedHashMap<>(System.getenv());
                for (String s : envList) {
                    int eq = s.indexOf('=');
                    if (eq > 0) merged.put(s.substring(0, eq), s.substring(eq + 1));
                }
                envp = new String[merged.size()];
                int i = 0;
                for (var e : merged.entrySet())
                    envp[i++] = fmt("%s=%s", e.getKey(), e.getValue());
            } else if (!envList.isEmpty()) {
                envp = envList.toArray(new String[0]);
            }
            var preserveFds = new int[preserveFdList.size()];
            for (int i = 0; i < preserveFds.length; i++)
                preserveFds[i] = preserveFdList.get(i);
            long[] rlimits = null;
            if (!rlimitList.isEmpty()) {
                rlimits = new long[rlimitList.size() * 3];
                for (int i = 0; i < rlimitList.size(); i++) {
                    long[] triple = rlimitList.get(i);
                    rlimits[i * 3] = triple[0];
                    rlimits[i * 3 + 1] = triple[1];
                    rlimits[i * 3 + 2] = triple[2];
                }
            }
            var result = nativeForkExec(argv, envp, directory, preserveFds, rlimits);
            if (result == null || result.length < 4 || result[0] <= 0)
                throw new IOException(fmt("nativeForkExec failed for %s", argv[0]));
            Log.i(TAG, fmt("Started process pid=%d: %s", result[0], String.join(" ", argv)));
            return new NativeProcess(result[0], result[1], result[2], result[3]);
        }
    }
}
