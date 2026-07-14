package cn.classfun.droidvm.ui.vm.display.vnc.display;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.ui.vm.display.base.DisplaySource;

/**
 * {@link DisplaySource} adapter for the VNC display path. The RFB connection, framebuffer bitmap
 * and reconnect logic live in BaseVncActivity (which owns the whole connection lifecycle, so
 * {@link #start()}/{@link #shutdown()} are no-ops here); the activity feeds framebuffer events in
 * through the dispatch methods. This keeps the VNC console speaking the same source language as
 * the native path until the connection plumbing itself moves in here with the base-activity
 * unification. No guest-resize channel (would be RFB desktop-size) yet.
 */
public final class VncBitmapSource implements DisplaySource {
    private final Callbacks callbacks;

    public VncBitmapSource(@NonNull Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    /** BaseVncActivity's onFramebufferReady hook lands here. */
    public void dispatchContentSize(int width, int height) {
        callbacks.onContentSize(width, height);
    }

    /** BaseVncActivity's status hook lands here (currently informational only). */
    public void dispatchState(@NonNull State state) {
        callbacks.onStateChanged(state);
    }

    @Override
    public void start() {
    }

    @Override
    public void shutdown() {
    }

    @Override
    public boolean supportsGuestResize() {
        return false;
    }

    @Override
    public void requestGuestResize(int width, int height) {
    }
}
