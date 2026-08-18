// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static android.view.Gravity.CENTER;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.vm.display.base.DisplayViewportController;

/**
 * Hosts the guest scanout on an external display. The activity hands these SurfaceViews to
 * {@link NativeSurfaceSource#retarget} and crosvm renders into them directly -- no copy, no
 * re-encode, because a Surface is just a BufferQueue producer handle and SurfaceFlinger composites
 * the resulting layer onto whichever display it belongs to.
 *
 * Not {@code DisplayPresentation}: that one is an ImageView fed with bitmaps, which is what the VNC
 * path needs (it decodes RFB frames on the CPU). Here the whole point is that no frame ever passes
 * through the app.
 *
 * Owns its own {@link DisplayViewportController} because the external display's size is unrelated to
 * the phone's, so the letterbox fit has to be computed independently. Zoom/pan gestures are never
 * fed to it -- there is nothing to gesture on over there -- so it stays permanently fitted.
 */
public final class NativeDisplayPresentation extends Presentation {
    private FrameLayout root;
    private SurfaceView scanoutView;
    private SurfaceView cursorView;
    private DisplayViewportController viewport;
    private final CursorOverlayPlacer cursorPlacer = new CursorOverlayPlacer();

    private int guestWidth;
    private int guestHeight;

    public NativeDisplayPresentation(@NonNull Context outerContext, @NonNull Display display,
                                     int guestWidth, int guestHeight) {
        super(outerContext, display);
        this.guestWidth = guestWidth;
        this.guestHeight = guestHeight;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.presentation_native_display);
        root = findViewById(R.id.presentation_root);
        scanoutView = findViewById(R.id.surface_view);
        cursorView = findViewById(R.id.cursor_view);

        // minAreaPx of 1: an external display is never squeezed by an IME or chrome, so the
        // degenerate-area freeze the phone needs has nothing to protect against here.
        viewport = new DisplayViewportController(1, new DisplayViewportController.Listener() {
            @Override
            public void onViewportChanged(int baseW, int baseH, float viewScale,
                                          float offsetX, float offsetY) {
                scanoutView.setLayoutParams(new FrameLayout.LayoutParams(baseW, baseH, CENTER));
                scanoutView.setScaleX(viewScale);
                scanoutView.setScaleY(viewScale);
                scanoutView.setTranslationX(offsetX);
                scanoutView.setTranslationY(offsetY);
                cursorPlacer.setViewport(baseW, baseH, viewScale, offsetX, offsetY);
            }

            @Override
            public void onGuestResizeWanted(int areaW, int areaH) {
                // No guest-resize channel on this path; the guest stays at its configured size and
                // the fit above scales it. Never called: auto-resize is not enabled.
            }
        });
        cursorPlacer.setCursorView(cursorView);
        cursorPlacer.setGuestSize(guestWidth, guestHeight);
        viewport.setContentSize(guestWidth, guestHeight);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            int w = r - l, h = b - t;
            v.post(() -> viewport.setArea(w, h));
        });
    }

    /** The view crosvm should scan out into. Valid after {@link #show()}. */
    @NonNull
    public SurfaceView getScanoutView() {
        return scanoutView;
    }

    /** The view carrying the guest's hardware cursor plane. Valid after {@link #show()}. */
    @NonNull
    public SurfaceView getCursorView() {
        return cursorView;
    }

    /** The guest resolution changed mid-session (UEFI modeset, guest-side xrandr, ...). */
    public void setContentSize(int width, int height) {
        guestWidth = width;
        guestHeight = height;
        if (viewport != null) {
            viewport.setContentSize(width, height);
            cursorPlacer.setGuestSize(width, height);
        }
    }

    /** Forwards a guest cursor position to this display's overlay. */
    public void onGuestCursorMoved(int gx, int gy) {
        cursorPlacer.onCursorMoved(gx, gy);
    }

    public int getDisplayId() {
        return getDisplay().getDisplayId();
    }
}
