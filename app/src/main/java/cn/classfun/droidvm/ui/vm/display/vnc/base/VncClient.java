// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.base;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class VncClient {
    private static final String TAG = "VncClient";

    static {
        System.loadLibrary("vnc_jni");
    }

    public interface NativeCallback {
        @SuppressWarnings("unused")
        void onFramebufferResized(int width, int height);

        @SuppressWarnings("unused")
        void onFramebufferUpdated(int x, int y, int w, int h);

        /**
         * One encoding-50 rect, whole: the eight-byte header (u32 BE length, u32 BE flags) and the
         * Annex-B payload behind it. Parsed on this side rather than in C so that there is one
         * parser and a test can feed it the seam's literal bytes.
         *
         * <p>[width] and [height] are the rect's, which for this encoding is the coded size of the
         * picture inside it. Called on the message-loop thread, and blocking here is how
         * backpressure reaches the server: the socket stops being drained.</p>
         */
        @SuppressWarnings("unused")
        void onH264Rect(@NonNull byte[] rect, int width, int height);

        /**
         * One 0x44564831 rect payload: the fixed four bytes of H264_SINGLE_PORT.md §1. Called on
         * the message-loop thread.
         */
        @SuppressWarnings("unused")
        void onDvhRect(@NonNull byte[] payload);
    }

    /**
     * Stops this process asking for the H.264 encodings on connections made from here on.
     *
     * <p>Process-wide because libvncclient's extension list is, and because the one fact that
     * justifies withdrawing them is process-wide too: a device with no {@code video/avc} decoder
     * has none for any console. It matters that the withdrawal happens rather than being merely
     * noted -- a client that asks for encoding 50 is served no pixels, so a console that cannot
     * decode and keeps asking is a console showing a frozen picture.</p>
     *
     * <p>Takes effect at the next {@link #connect}; a connection already up keeps what it
     * negotiated.</p>
     */
    public static void setH264Advertised(boolean advertised) {
        nativeSetH264Advertised(advertised);
    }

    private long nativeHandle;

    public VncClient() {
        nativeHandle = nativeCreate();
        if (nativeHandle == 0) throw new RuntimeException("Failed to create VNC client");
        Log.i(TAG, fmt("created with handle=%d", nativeHandle));
    }

    public boolean connect(
        @NonNull String host,
        int port,
        @Nullable String password,
        @NonNull NativeCallback callback
    ) {
        if (nativeHandle == 0) return false;
        return nativeConnect(nativeHandle, host, port, password, callback);
    }

    public int processMessages() {
        if (nativeHandle == 0) return -1;
        return nativeProcessMessages(nativeHandle);
    }

    public void sendPointer(int x, int y, int buttonMask) {
        if (nativeHandle == 0) return;
        nativeSendPointer(nativeHandle, x, y, buttonMask);
    }

    public void sendKey(int keysym, boolean down) {
        if (nativeHandle == 0) return;
        nativeSendKey(nativeHandle, keysym, down);
    }

    public void copyPixels(@NonNull Bitmap dst) {
        if (nativeHandle == 0) return;
        nativeCopyPixels(nativeHandle, dst);
    }

    public int getWidth() {
        return nativeHandle == 0 ? 0 : nativeGetWidth(nativeHandle);
    }

    public int getHeight() {
        return nativeHandle == 0 ? 0 : nativeGetHeight(nativeHandle);
    }

    public boolean isConnected() {
        return nativeHandle != 0 && nativeIsConnected(nativeHandle);
    }

    public void requestStop() {
        if (nativeHandle == 0) return;
        nativeRequestStop(nativeHandle);
    }

    public void disconnect() {
        long h = nativeHandle;
        nativeHandle = 0;
        if (h != 0) {
            nativeDisconnect(h);
            Log.i(TAG, "disconnected");
        }
    }

    private static native void nativeSetH264Advertised(boolean advertised);

    private static native long nativeCreate();

    private static native boolean nativeConnect(long handle, String host, int port, String password, NativeCallback cb);

    private static native int nativeProcessMessages(long handle);

    private static native void nativeSendPointer(long handle, int x, int y, int mask);

    private static native void nativeSendKey(long handle, int keysym, boolean down);

    private static native void nativeCopyPixels(long handle, Bitmap bitmap);

    private static native int nativeGetWidth(long handle);

    private static native int nativeGetHeight(long handle);

    private static native boolean nativeIsConnected(long handle);

    private static native void nativeRequestStop(long handle);

    private static native void nativeDisconnect(long handle);
}
