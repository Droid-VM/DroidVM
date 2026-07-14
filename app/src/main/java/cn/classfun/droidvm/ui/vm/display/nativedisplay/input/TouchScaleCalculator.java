package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

/**
 * Computes per-axis touch scale from view size to the guest device's fixed normalized ABS range
 * ({@link EvdevEncoder#NORMALIZED_ABS_MAX}) — independent of the guest resolution, so it survives
 * guest auto-resize. The touch listener is attached to the SurfaceView, which is itself sized to
 * the guest aspect ratio (see {@code VMNativeDisplayActivity#updateAspectRatio}), so its bounds
 * carry no letterbox/pillarbox bars: there is no offset and scale is simply NORMALIZED_ABS_MAX/view
 * per axis.
 */
public final class TouchScaleCalculator {
    private TouchScaleCalculator() {
    }

    public static final class TouchTransform {
        public final float scaleX;
        public final float scaleY;

        TouchTransform(float scaleX, float scaleY) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }
    }

    public static TouchTransform compute(int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return new TouchTransform(1f, 1f);
        }
        // Normalize view coords to the fixed ABS range (EvdevEncoder.NORMALIZED_ABS_MAX): the guest
        // then maps them 1:1 to its screen at ANY resolution, so no guest resolution is needed here.
        // Auto-resize just changes the view size, which this already tracks per call.
        return new TouchTransform(
            (float) EvdevEncoder.NORMALIZED_ABS_MAX / viewWidth,
            (float) EvdevEncoder.NORMALIZED_ABS_MAX / viewHeight);
    }
}
