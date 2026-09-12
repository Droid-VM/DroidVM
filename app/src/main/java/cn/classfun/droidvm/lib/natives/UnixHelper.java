// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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

    public static native int nativePollIn(int fd, int timeoutMs);

    /**
     * The kernel's uevent multicast socket, or -1. A driver bind or unbind moves no node, so it
     * raises no inotify event anywhere: this is the only report of one there is.
     */
    public static native int nativeUeventOpen();

    /**
     * poll() over two descriptors: 1 for the first readable, 2 for the second, 3 for both, 0 on
     * timeout, -1 on error, -2 on hangup. For a reader that must be stoppable at once without
     * waking on a timer to ask whether it should stop.
     */
    public static native int nativePollIn2(int fd1, int fd2, int timeoutMs);

    public static native int nativeRead(int fd, @NonNull byte[] buf, int len);

    public static native int nativeWrite(int fd, @NonNull byte[] buf, int len);

    /**
     * Opens an evdev node ({@code /dev/input/eventN}) read-write, falling back to read-only, or
     * -1. Read and write it with {@link #nativeRead} / {@link #nativeWrite}: the records are
     * 24-byte {@code struct input_event}s on this ABI.
     */
    public static native int nativeEvdevOpen(@NonNull String path);

    /**
     * EVIOCGRAB: makes [fd] the sole recipient of the device's events, or hands it back. 0 on
     * success, -errno otherwise (-EBUSY when another process already holds the grab). Everyone
     * else -- Android's own InputReader included -- keeps its descriptor open and simply stops
     * being woken, which is what lets a guest have the keys Android would otherwise keep.
     */
    public static native int nativeEvdevGrab(int fd, boolean grab);

    /** The device's name (EVIOCGNAME), or null. */
    @Nullable
    public static native String nativeEvdevName(int fd);

    /** {bustype, vendor, product, version} (EVIOCGID), or null. */
    @Nullable
    public static native int[] nativeEvdevIds(int fd);

    /** The EV_KEY bitmap (EVIOCGBIT), one bit per key code, or null. */
    @Nullable
    public static native byte[] nativeEvdevKeyBits(int fd);

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

