package cn.classfun.droidvm.lib.run.root;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.Shell;

import cn.classfun.droidvm.lib.run.RunContext;
import cn.classfun.droidvm.lib.run.RunResult;

public final class RootRunContext extends RunContext {
    private static RootRunContext instance = null;

    private RootRunContext() {
    }

    @NonNull
    public static RunContext getContext() {
        synchronized (RootRunContext.class) {
            if (instance == null)
                instance = new RootRunContext();
        }
        return instance;
    }

    @NonNull
    @Override
    protected RunResult runInternal(@NonNull String command) {
        return new RootRunResult(Shell.cmd(command).exec());
    }
}
