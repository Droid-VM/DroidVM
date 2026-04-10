package cn.classfun.droidvm.lib.run;

import android.util.Log;

import androidx.annotation.NonNull;

public abstract class RunResult {
    public abstract int getCode();

    public abstract Iterable<String> getOut();

    public abstract Iterable<String> getErr();

    public final boolean isSuccess() {
        return getCode() == 0;
    }

    public final void printLog(@NonNull String tag) {
        getOut().forEach(line -> {
            line = line.trim();
            if (line.isEmpty()) return;
            Log.i(tag, line);
        });
        getErr().forEach(line -> {
            line = line.trim();
            if (line.isEmpty()) return;
            Log.w(tag, line);
        });
    }

    @NonNull
    public String getOutString() {
        return String.join("\n", getOut()).trim();
    }

    @NonNull
    public String getErrString() {
        return String.join("\n", getOut()).trim();
    }
}
