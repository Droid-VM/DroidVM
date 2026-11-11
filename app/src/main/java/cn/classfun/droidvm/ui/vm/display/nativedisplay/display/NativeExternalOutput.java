// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static android.view.Gravity.CENTER;
import static android.view.Gravity.START;
import static android.view.Gravity.TOP;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.ui.vm.display.base.DaemonDisplayAttach;

/**
 * A VM screen's native picture on an external display: a scanout SurfaceView handed to crosvm the
 * way the console hands its own ({@link DisplayProvider}, the same per-screen display service),
 * fitted into the display at the guest's aspect, and the guest's cursor plane above it.
 *
 * <p>Lives in a window the console activity does not own ({@code ExternalOutputService}'s
 * presentation), so it reaches the daemon's broker by itself. The console holds its own
 * provider back meanwhile ({@link DisplayProvider#setHeld}): crosvm has one scanout slot a
 * screen.</p>
 */
public final class NativeExternalOutput {
    private static final String TAG = "NativeExternalOutput";
    /** Where the cursor waits while the guest shows none: off the display, its Surface kept. */
    private static final float CURSOR_PARKED_PX = -10000f;

    private final Context context;
    private final String vmKey;
    private final FrameLayout root;
    private final SurfaceView picture;
    private final SurfaceView cursor;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable
    private DaemonDisplayAttach attach;
    @Nullable
    private DisplayProvider provider;
    private int guestWidth;
    private int guestHeight;
    private int cursorX = -1;
    private int cursorY = -1;

    /**
     * @param root the window's content, which this fills with the picture and the cursor
     */
    public NativeExternalOutput(@NonNull Context context, @NonNull String vmId,
                                @NonNull String screenId, @NonNull FrameLayout root,
                                int guestWidth, int guestHeight) {
        this.context = context;
        this.vmKey = NativeDisplay.serviceNameFromId(vmId, screenId);
        this.root = root;
        this.guestWidth = Math.max(1, guestWidth);
        this.guestHeight = Math.max(1, guestHeight);
        picture = new SurfaceView(context);
        root.addView(picture, new FrameLayout.LayoutParams(1, 1, CENTER));
        cursor = new SurfaceView(context);
        int plane = DisplayProvider.CURSOR_PLANE_PX;
        root.addView(cursor, new FrameLayout.LayoutParams(plane, plane, TOP | START));
        parkCursor();
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit());
    }

    /** Reaches the daemon's broker and, through it, the screen's display service. */
    public void start() {
        if (attach != null) return;
        attach = new DaemonDisplayAttach(context, main, new DaemonDisplayAttach.Listener() {
            @Override
            public void onAttached(@NonNull INativeDisplayRootService service) {
                if (provider != null) return;
                provider = new DisplayProvider(picture, guestWidth, guestHeight, () -> {
                    var broker = attach == null ? null : attach.getService();
                    if (broker == null) return null;
                    try {
                        return broker.waitForDisplayBinder(vmKey);
                    } catch (Exception e) {
                        return null;
                    }
                },
                    connected -> Log.i(TAG, fmt("%s %s", vmKey,
                        connected ? "connected" : "lost its display service")),
                    config -> {
                        guestWidth = Math.max(1, config.width);
                        guestHeight = Math.max(1, config.height);
                        fit();
                    });
                provider.setCursorView(cursor);
                provider.setCursorListener(NativeExternalOutput.this::onCursorMoved);
            }

            @Override
            public void onLost() {
            }
        });
        attach.start();
    }

    public void stop() {
        if (provider != null) {
            provider.shutdown();
            provider = null;
        }
        if (attach != null) {
            attach.stop();
            attach = null;
        }
    }

    /** The picture at the guest's aspect, as large as the display allows, centred. */
    private void fit() {
        int areaW = root.getWidth();
        int areaH = root.getHeight();
        if (areaW <= 0 || areaH <= 0) return;
        float scale = Math.min(areaW / (float) guestWidth, areaH / (float) guestHeight);
        int w = Math.max(1, Math.round(guestWidth * scale));
        int h = Math.max(1, Math.round(guestHeight * scale));
        var lp = picture.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            picture.setLayoutParams(new FrameLayout.LayoutParams(w, h, CENTER));
        }
        placeCursor();
    }

    private void onCursorMoved(int x, int y) {
        // u32::MAX twice (-1 as Java's int): the guest has hidden its pointer.
        cursorX = x;
        cursorY = y;
        placeCursor();
    }

    /** The cursor plane where the guest says its pointer is, scaled as the picture is. */
    private void placeCursor() {
        if (cursorX < 0 || cursorY < 0) {
            parkCursor();
            return;
        }
        int areaW = root.getWidth();
        int areaH = root.getHeight();
        if (areaW <= 0 || areaH <= 0) return;
        float scale = Math.min(areaW / (float) guestWidth, areaH / (float) guestHeight);
        float left = (areaW - guestWidth * scale) / 2f;
        float top = (areaH - guestHeight * scale) / 2f;
        cursor.setPivotX(0f);
        cursor.setPivotY(0f);
        cursor.setScaleX(scale);
        cursor.setScaleY(scale);
        cursor.setTranslationX(left + cursorX * scale);
        cursor.setTranslationY(top + cursorY * scale);
    }

    private void parkCursor() {
        cursor.setTranslationX(CURSOR_PARKED_PX);
        cursor.setTranslationY(CURSOR_PARKED_PX);
    }
}
