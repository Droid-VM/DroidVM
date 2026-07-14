package cn.classfun.droidvm.ui.vm.display.base;

import androidx.annotation.NonNull;

/**
 * Single source of truth for the VM display viewport: where the guest image sits inside the
 * display area, at what scale, under every combination of chrome (toolbar/status bar/extra
 * keys/IME/system bars) visibility. Pure math, no Android view types, so the rules are unit
 * testable; the owning activity feeds it sizes and gestures and applies the emitted geometry.
 *
 * State model: {@code scale} is absolute (screen px per content px) and {@code offset} is the
 * content center relative to the display-area center. The invariant rules:
 *
 * <ul>
 *   <li>Fitted (scale at the letterbox fit): any display-area change re-fits automatically, so
 *       a "fit to window" view always stays fit-to-window.</li>
 *   <li>Zoomed: a display-area change keeps the absolute scale and the center offset - the image
 *       does not change size on screen, its center just follows the area center - then the offset
 *       is clamped back into the pan bounds. If the area grew enough that the fit scale catches
 *       up with the current scale, it snaps back to fitted.</li>
 *   <li>Degenerate area (e.g. landscape with a tall IME squeezing the container below
 *       {@code minAreaPx}, possibly to zero or negative): freeze. No geometry is recomputed or
 *       emitted, everything holds its last good position until the area recovers.</li>
 *   <li>Auto-resize mode (guest display follows the area): zoom gestures are disabled, every
 *       accepted area change re-fits and asks the listener to resize the guest. Debouncing the
 *       guest resize request is the caller's job.</li>
 * </ul>
 *
 * The emitted geometry maps onto the existing view mechanics: lay the content view out at
 * {@code baseW x baseH} centered in the container (the letterbox fit), then apply
 * {@code viewScale} and translate by {@code offset}.
 */
public final class DisplayViewportController {
    /** Maximum zoom, relative to the letterbox fit scale. */
    public static final float MAX_ZOOM = 5f;
    // A scale within 0.1% of the fit scale counts as fitted (float noise + snap-to-fit).
    private static final float FIT_SNAP = 1.001f;

    public interface Listener {
        /**
         * Apply the viewport: content view laid out at baseW x baseH centered in the area, then
         * scaled by viewScale (1 = fitted) and translated by offset px.
         */
        void onViewportChanged(int baseW, int baseH, float viewScale, float offsetX, float offsetY);

        /** Auto-resize mode only: the area changed; ask the guest display to match it. */
        void onGuestResizeWanted(int areaW, int areaH);
    }

    private final int minAreaPx;
    private final Listener listener;

    private int contentW, contentH; // guest/framebuffer resolution
    private int areaW, areaH;       // last accepted (non-degenerate) display area
    private float scale;            // absolute: screen px per content px
    private float offsetX, offsetY; // content center relative to area center, screen px
    private boolean autoResize;
    private boolean frozen;

    /**
     * @param minAreaPx area dimensions below this freeze the viewport instead of re-laying out.
     */
    public DisplayViewportController(int minAreaPx, @NonNull Listener listener) {
        this.minAreaPx = Math.max(1, minAreaPx);
        this.listener = listener;
    }

    /** Guest/framebuffer resolution known or changed. Always re-fits (a resolution change is a
     *  content-level event; in auto-resize mode it is the ack of a resize request). */
    public void setContentSize(int w, int h) {
        if (w <= 0 || h <= 0 || (w == contentW && h == contentH)) {
            return;
        }
        contentW = w;
        contentH = h;
        if (areaW <= 0 || areaH <= 0) {
            // No display area yet (content size can arrive before the first layout pass, e.g.
            // from the launch intent in onCreate): fitScale() would be 0 and the emitted
            // viewScale NaN. Hold; the first setArea() re-fits and emits.
            return;
        }
        fit();
        emit();
    }

    /** Display-area (container) size changed. This is the single entry point for every chrome/IME/
     *  rotation-driven layout change; the caller does not need to know what caused it. */
    public void setArea(int w, int h) {
        if (w < minAreaPx || h < minAreaPx) {
            // Degenerate (possibly zero/negative under an oversized IME): hold everything.
            frozen = true;
            return;
        }
        frozen = false;
        if (w == areaW && h == areaH) {
            return; // no-op; layout listeners re-fire liberally
        }
        boolean hadArea = areaW > 0 && areaH > 0;
        boolean wasFitted = hadArea && ready() && scale <= fitScale() * FIT_SNAP;
        areaW = w;
        areaH = h;
        if (!ready()) {
            return;
        }
        if (autoResize) {
            fit();
            emit();
            listener.onGuestResizeWanted(w, h);
            return;
        }
        if (!hadArea || wasFitted || scale <= fitScale() * FIT_SNAP) {
            // Was fit-to-window, or the area grew past the current zoom: back to fit.
            fit();
        } else {
            // Zoomed: same on-screen size, same center offset, clamped into the new bounds.
            clampOffset();
        }
        emit();
    }

    /**
     * Three-finger zoom/pan gesture step. {@code scaleFactor} is relative to the current scale;
     * dx/dy are pan deltas in screen px. Ignored while frozen or in auto-resize mode.
     */
    public void onZoomPan(float scaleFactor, float dxPx, float dyPx) {
        if (!ready() || areaW == 0 || frozen || autoResize) {
            return;
        }
        float fit = fitScale();
        float next = clamp(scale * scaleFactor, fit, fit * MAX_ZOOM);
        if (next <= fit * FIT_SNAP) {
            fit(); // snap back to fit; a fitted view has nothing to pan
        } else {
            scale = next;
            offsetX += dxPx;
            offsetY += dyPx;
            clampOffset();
        }
        emit();
    }

    /** Back to fit-to-window (e.g. on input-mode switch). */
    public void resetToFit() {
        if (!ready() || areaW == 0) {
            return;
        }
        fit();
        emit();
    }

    /** Guest display follows the area: zoom disabled, area changes request a guest resize. */
    public void setAutoResize(boolean enabled) {
        if (autoResize == enabled) {
            return;
        }
        autoResize = enabled;
        if (enabled && ready() && areaW > 0) {
            fit();
            emit();
            listener.onGuestResizeWanted(areaW, areaH);
        }
    }

    public boolean isFitted() {
        return ready() && areaW > 0 && scale <= fitScale() * FIT_SNAP;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /** Absolute scale, screen px per content px. */
    public float getScale() {
        return scale;
    }

    private boolean ready() {
        return contentW > 0 && contentH > 0;
    }

    private float fitScale() {
        return Math.min(areaW / (float) contentW, areaH / (float) contentH);
    }

    private void fit() {
        scale = fitScale();
        offsetX = 0;
        offsetY = 0;
    }

    // Pan bounds: the image may only pan along an axis where it overflows the area, and never far
    // enough to pull its edge inside the area (no gaps while zoomed). Letterboxed axes lock to 0.
    private void clampOffset() {
        float halfGapX = Math.max(0, (contentW * scale - areaW) / 2f);
        float halfGapY = Math.max(0, (contentH * scale - areaH) / 2f);
        offsetX = clamp(offsetX, -halfGapX, halfGapX);
        offsetY = clamp(offsetY, -halfGapY, halfGapY);
    }

    private void emit() {
        float fit = fitScale();
        int baseW = Math.round(contentW * fit);
        int baseH = Math.round(contentH * fit);
        listener.onViewportChanged(baseW, baseH, scale / fit, offsetX, offsetY);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(v, hi));
    }
}
