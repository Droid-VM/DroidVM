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
