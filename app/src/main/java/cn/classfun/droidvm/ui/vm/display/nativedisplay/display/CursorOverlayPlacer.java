// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Places the guest's hardware-cursor overlay over a scanout view, given the guest cursor position
 * and the viewport transform currently applied to that scanout.
 *
 * Extracted from the display activity because casting needs TWO of these at once: the phone's
 * display area and the external display's {@code Presentation} have independent sizes and viewport
 * transforms, so the geometry cannot be a single set of fields on the activity.
 *
 * The transform mirrors what the scanout itself gets: content is centred in its area, scaled about
 * that centre, then displaced by the pan offset. The overlay is scaled too -- the cursor image is in
 * guest pixels, so at 2x zoom it has to double like everything else or the pointer shrinks relative
 * to what it points at. Pivot is (0,0) so scaling grows the image away from the hotspot corner
 * rather than around its middle.
 *
 * Main thread only.
 */
final class CursorOverlayPlacer {
    @Nullable
    private SurfaceView cursorView;

    // Last viewport emitted for the scanout this placer follows.
    private float baseW, baseH, viewScale = 1f, offsetX, offsetY;
    // Guest framebuffer resolution the cursor positions are expressed in.
    private int guestW, guestH;
    // Last guest cursor position; -1 means "guest hid its pointer" (see onCursorMoved).
    private int cursorX = -1, cursorY = -1;

    /** The overlay view, or null to place nothing. */
    void setCursorView(@Nullable SurfaceView view) {
        cursorView = view;
        apply();
    }

    /** Guest resolution changed; cursor positions are in this coordinate space. */
    void setGuestSize(int width, int height) {
        guestW = width;
        guestH = height;
        apply();
    }

    /** The viewport transform applied to the scanout view changed. */
    void setViewport(int baseW, int baseH, float viewScale, float offsetX, float offsetY) {
        this.baseW = baseW;
        this.baseH = baseH;
        this.viewScale = viewScale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        apply();
    }

    /**
     * A new guest cursor position.
     *
     * crosvm sends u32::MAX,MAX when the guest hides its pointer (UPDATE_CURSOR with resource_id=0,
     * which is what switching to a text console does). Without acting on it the overlay keeps
     * showing the last cursor image on a console that should have none. A real position is a
     * framebuffer coordinate, so that value can never be a genuine one. It arrives as -1 in Java's
     * signed int.
     */
    void onCursorMoved(int gx, int gy) {
        if (gx == -1 && gy == -1) {
            cursorX = -1;
            cursorY = -1;
            if (cursorView != null) cursorView.setVisibility(GONE);
            return;
        }
        cursorX = gx;
        cursorY = gy;
        apply();
    }

    /** Hides the overlay and forgets the position, e.g. when this target stops being the scanout. */
    void reset() {
        cursorX = -1;
        cursorY = -1;
        if (cursorView != null) cursorView.setVisibility(GONE);
    }

    /** Where and how big to draw the overlay, in the scanout parent's coordinate space. */
    static final class Placement {
        final float translationX;
        final float translationY;
        final float scale;

        Placement(float translationX, float translationY, float scale) {
            this.translationX = translationX;
            this.translationY = translationY;
            this.scale = scale;
        }
    }

    /**
     * The geometry, kept free of view types so it is directly testable.
     *
     * @param areaW/areaH the scanout parent's size in px
     * @return null when the inputs cannot produce a placement (no size, no position yet)
     */
    static Placement compute(int cursorX, int cursorY, int guestW, int guestH,
                             float baseW, float baseH, float viewScale,
                             float offsetX, float offsetY, int areaW, int areaH) {
        if (cursorX < 0 || cursorY < 0 || guestW <= 0 || guestH <= 0
            || baseW <= 0 || baseH <= 0 || areaW <= 0 || areaH <= 0) {
            return null;
        }
        float vx = cursorX * baseW / guestW;
        float vy = cursorY * baseH / guestH;
        float cx = areaW / 2f + offsetX + (vx - baseW / 2f) * viewScale;
        float cy = areaH / 2f + offsetY + (vy - baseH / 2f) * viewScale;
        return new Placement(cx, cy, (baseW / guestW) * viewScale);
    }

    private void apply() {
        var view = cursorView;
        if (view == null) {
            return;
        }
        var area = (android.view.View) view.getParent();
        if (area == null) {
            return;
        }
        var p = compute(cursorX, cursorY, guestW, guestH, baseW, baseH, viewScale,
            offsetX, offsetY, area.getWidth(), area.getHeight());
        if (p == null) {
            return;
        }
        view.setPivotX(0f);
        view.setPivotY(0f);
        view.setScaleX(p.scale);
        view.setScaleY(p.scale);
        view.setTranslationX(p.translationX);
        view.setTranslationY(p.translationY);
        if (view.getVisibility() != VISIBLE) {
            view.setVisibility(VISIBLE);
        }
    }

    /** True once a real guest cursor position has been seen (i.e. the guest uses the plane). */
    boolean hasPosition() {
        return cursorX >= 0;
    }

    int getCursorX() {
        return cursorX;
    }

    int getCursorY() {
        return cursorY;
    }

    @NonNull
    @Override
    public String toString() {
        return "CursorOverlayPlacer{" + cursorX + "," + cursorY + "}";
    }
}
