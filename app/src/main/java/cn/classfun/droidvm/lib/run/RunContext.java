package cn.classfun.droidvm.lib.run;

import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

public abstract class RunContext {
    private static final String TAG = "RunContext";
    protected abstract RunResult runInternal(String command);

    public final RunResult run(String command) {
        Log.i(TAG, fmt("Running command: %s", command));
        return runInternal(command);
    }

    public final RunResult runQuiet(String command) {
        return runInternal(command);
    }

    @NonNull
    public final RunResult run(@NonNull String format, Object... args) {
        return run(fmt(format, args));
    }

    @NonNull
    public final RunResult runQuiet(@NonNull String format, Object... args) {
        return runQuiet(fmt(format, args));
    }

    public final RunResult runList(@NonNull Iterable<String> command) {
        var sb = new StringBuilder();
        command.forEach(line -> sb.append(escapedString(line)).append(" "));
        return run(sb.toString().trim());
    }

    public final RunResult runList(@NonNull String... command) {
        var sb = new StringBuilder();
        for (String line : command) sb.append(escapedString(line)).append(" ");
        return run(sb.toString().trim());
    }

    public final RunResult runListQuiet(@NonNull Iterable<String> command) {
        var sb = new StringBuilder();
        command.forEach(line -> sb.append(escapedString(line)).append(" "));
        return runQuiet(sb.toString().trim());
    }

    public final RunResult runListQuiet(@NonNull String... command) {
        var sb = new StringBuilder();
        for (String line : command) sb.append(escapedString(line)).append(" ");
        return runQuiet(sb.toString().trim());
    }
}
