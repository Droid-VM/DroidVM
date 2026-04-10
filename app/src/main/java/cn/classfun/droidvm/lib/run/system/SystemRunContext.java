package cn.classfun.droidvm.lib.run.system;

import static java.util.Objects.requireNonNullElse;
import static cn.classfun.droidvm.lib.utils.StringUtils.streamToString;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.run.RunContext;
import cn.classfun.droidvm.lib.run.RunResult;
import cn.classfun.droidvm.lib.utils.FileUtils;

public final class SystemRunContext extends RunContext {
    private static SystemRunContext instance = null;
    private static String shell = null;

    private SystemRunContext() {
    }

    @NonNull
    public static RunContext getContext() {
        synchronized (SystemRunContext.class) {
            if (instance == null)
                instance = new SystemRunContext();
        }
        return instance;
    }

    @NonNull
    private static String getShell() {
        if (shell != null) return shell;
        var sh = requireNonNullElse(System.getenv("SHELL"), "sh");
        if (!sh.contains("/"))
            sh = FileUtils.findExecute(sh);
        shell = sh;
        return shell;
    }

    @NonNull
    @Override
    protected RunResult runInternal(@NonNull String command) {
        return runSimple(command);
    }

    @NonNull
    private static RunResult runSimple(@NonNull String command) {
        int code = -1;
        String out = "", err;
        try {
            var rt = Runtime.getRuntime();
            var cmd = new String[]{getShell(), "-c", command};
            var process = rt.exec(cmd);
            code = process.waitFor();
            out = streamToString(process.getInputStream());
            err = streamToString(process.getErrorStream());
        } catch (Exception e) {
            err = requireNonNullElse(e.getMessage(), e.toString());
        }
        return new SystemRunResult(code, out, err);
    }
}
