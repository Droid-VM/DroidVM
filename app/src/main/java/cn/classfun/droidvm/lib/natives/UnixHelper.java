package cn.classfun.droidvm.lib.natives;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class UnixHelper {
    private static final String TAG = "UnixHelper";
    private static final String LIB_NAME = "libunixhelper.so";
    private static volatile boolean loaded = false;

    public interface SignalCallback {
        void onSignal(int signum);
    }

    private static native void nativeInstallSignalHandler(
        @NonNull String signalName, @NonNull SignalCallback callback
    );

    @SuppressWarnings("unused")
    public static native int nativeGetPid();

    @SuppressWarnings("unused")
    public static native int nativeUnixListen(@NonNull String path);

    @SuppressWarnings("unused")
    public static native int nativeUnixAccept(int serverFd);

    @SuppressWarnings("unused")
    public static native void nativeCloseFd(int fd);

    @Nullable
    @SuppressWarnings("unused")
    public static native int[] nativeSocketPair(int af, int type, int protocol);

    @Nullable
    @SuppressWarnings("unused")
    public static native int[] nativePipe();

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    public static void load() {
        if (loaded) return;
        var libPath = pathJoin(DATA_DIR, "lib", LIB_NAME);
        try {
            System.load(libPath);
            loaded = true;
            Log.i(TAG, fmt("Loaded native library: %s", libPath));
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, fmt("Failed to load native library: %s", libPath), e);
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static void installSignalHandler(
        @NonNull String signal, @NonNull SignalCallback callback
    ) {
        if (!loaded) {
            Log.w(TAG, fmt("Cannot install %s handler: native library not loaded", signal));
            return;
        }
        try {
            nativeInstallSignalHandler(signal, callback);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to install %s handler", signal), e);
        }
    }
}

