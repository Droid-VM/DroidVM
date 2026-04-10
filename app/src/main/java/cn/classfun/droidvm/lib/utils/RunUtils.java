package cn.classfun.droidvm.lib.utils;

import static java.util.Objects.requireNonNullElse;

import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

import cn.classfun.droidvm.lib.run.RunContext;
import cn.classfun.droidvm.lib.run.RunResult;
import cn.classfun.droidvm.lib.run.root.RootRunContext;
import cn.classfun.droidvm.lib.run.system.SystemRunContext;

public final class RunUtils {
    private RunUtils() {
    }

    @NonNull
    private static RunContext getContext() {
        return (Os.getuid() == 0) ?
            SystemRunContext.getContext() :
            RootRunContext.getContext();
    }

    @NonNull
    public static List<String> outStringToList(@Nullable String s) {
        return Arrays.asList(requireNonNullElse(s, "").split("\n"));
    }

    @NonNull
    public static String escapedString(@NonNull String s) {
        var sb = new StringBuilder();
        sb.append('\'');
        int len = s.length();
        for (int i = 0; i < len; ++i) {
            char c = s.charAt(i);
            if (c == '\'') {
                sb.append("'\\''");
                continue;
            }
            sb.append(c);
        }
        sb.append('\'');
        return sb.toString();
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult run(@NonNull String command) {
        return getContext().run(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult run(@NonNull String format, Object... args) {
        return getContext().run(format, args);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runList(@NonNull Iterable<String> command) {
        return getContext().runList(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runList(@NonNull String... command) {
        return getContext().runList(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runQuiet(@NonNull String command) {
        return getContext().runQuiet(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runQuiet(@NonNull String format, Object... args) {
        return getContext().runQuiet(format, args);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runListQuiet(@NonNull Iterable<String> command) {
        return getContext().runListQuiet(command);
    }

    @NonNull
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public static RunResult runListQuiet(@NonNull String... command) {
        return getContext().runListQuiet(command);
    }
}
