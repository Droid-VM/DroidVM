package cn.classfun.droidvm.lib.run.system;

import static cn.classfun.droidvm.lib.utils.RunUtils.outStringToList;

import androidx.annotation.NonNull;

import java.util.List;

import cn.classfun.droidvm.lib.run.RunResult;

public final class SystemRunResult extends RunResult {
    private final int code;
    private final String out;
    private final String err;
    private List<String> outList = null;
    private List<String> errList = null;

    SystemRunResult(int code, String out, String err) {
        this.code = code;
        this.out = out;
        this.err = err;
    }

    @Override
    public int getCode() {
        return code;
    }

    @NonNull
    @Override
    public Iterable<String> getOut() {
        if (outList == null)
            outList = outStringToList(out);
        return outList;
    }

    @NonNull
    @Override
    public Iterable<String> getErr() {
        if (errList == null)
            errList = outStringToList(err);
        return errList;
    }
}