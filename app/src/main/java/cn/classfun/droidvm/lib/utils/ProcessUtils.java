package cn.classfun.droidvm.lib.utils;

import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

@SuppressWarnings({"unused", "UnusedReturnValue"})
public final class ProcessUtils {
    private static final String TAG = "ProcessUtils";
    public static final int SIGHUP = 1;
    public static final int SIGINT = 2;
    public static final int SIGQUIT = 3;
    public static final int SIGILL = 4;
    public static final int SIGTRAP = 5;
    public static final int SIGABRT = 6;
    public static final int SIGBUS = 7;
    public static final int SIGFPE = 8;
    public static final int SIGKILL = 9;
    public static final int SIGUSR1 = 10;
    public static final int SIGSEGV = 11;
    public static final int SIGUSR2 = 12;
    public static final int SIGPIPE = 13;
    public static final int SIGALRM = 14;
    public static final int SIGTERM = 15;
    public static final int SIGSTKFLT = 16;
    public static final int SIGCHLD = 17;
    public static final int SIGCONT = 18;
    public static final int SIGSTOP = 19;
    public static final int SIGTSTP = 20;
    public static final int SIGTTIN = 21;
    public static final int SIGTTOU = 22;
    public static final int SIGURG = 23;
    public static final int SIGXCPU = 24;
    public static final int SIGXFSZ = 25;
    public static final int SIGVTALRM = 26;
    public static final int SIGPROF = 27;
    public static final int SIGWINCH = 28;
    public static final int SIGIO = 29;
    public static final int SIGPWR = 30;
    public static final int SIGSYS = 31;
    public static final int SIGRTMIN = 34;
    public static final int SIGRTMAX = 64;

    private ProcessUtils() {
    }

    public static boolean isPidExists(int pid) {
        return shellCheckExists(fmt("/proc/%d/cmdline", pid));
    }

    public static boolean shellKillProcess(int pid, int signal) {
        if (pid <= 1) throw new IllegalArgumentException(fmt("Invalid PID: %d", pid));
        return runList("kill", fmt("-%d", signal), String.valueOf(pid)).isSuccess();
    }

    public static boolean shellKillProcess(int pid) {
        return shellKillProcess(pid, SIGTERM);
    }

    public static boolean shellKillProcessFile(@NonNull String pidFile, int signal) {
        try {
            var content = shellReadFile(pidFile);
            var pid = parseInt(content.trim());
            return shellKillProcess(pid, signal);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to kill process from pid file: %s", pidFile), e);
            return false;
        }
    }

    public static boolean shellKillProcessFile(@NonNull String pidFile) {
        return shellKillProcessFile(pidFile, SIGTERM);
    }
}
