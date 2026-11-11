// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.display;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.ui.vm.display.base.DisplayPresentation;
import cn.classfun.droidvm.ui.vm.display.vnc.base.VncSession;
import cn.classfun.droidvm.ui.vm.display.vnc.base.VncStatus;

/**
 * A VM screen's VNC picture on an external display: the screen's session -- the one the console
 * uses too, if it is open (VncSession.acquire) -- drawn into a {@link DisplayPresentation}, its
 * framebuffer in the image view and the H.264 stream, when there is one, in the decoder view
 * letterboxed over it. The console keeps the input; this window has the picture.
 */
public final class VncExternalOutput implements VncSession.Listener {
    private final DisplayPresentation window;
    @Nullable
    private VncSession session;

    public VncExternalOutput(@NonNull DisplayPresentation window) {
        this.window = window;
    }

    /** Joins [vmId]'s [screenId] session and takes its decoder view; after the window is shown. */
    public void start(@NonNull Context context, @NonNull String vmId, @NonNull String screenId) {
        if (session != null) return;
        session = VncSession.acquire(context, vmId, screenId, this);
        session.setH264View(window.getH264View());
    }

    public void stop() {
        var s = session;
        session = null;
        if (s == null) return;
        s.releaseH264View(window.getH264View());
        s.release(this);
    }

    @Override
    public void onStatus(@NonNull String text, @NonNull VncStatus status) {
    }

    @Override
    public void onBitmapUpdated(@NonNull Bitmap bitmap) {
        window.updateBitmap(bitmap);
    }

    @Override
    public void onClearDisplay() {
        window.clearBitmap();
    }

    @Override
    public void onH264StreamChanged(boolean live, int width, int height) {
        if (live) window.fitH264(width, height);
    }
}
