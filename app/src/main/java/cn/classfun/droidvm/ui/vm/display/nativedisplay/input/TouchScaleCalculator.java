package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

/**
 * Computes per-axis touch scale from view size to guest resolution. The touch listener is attached
 * to the SurfaceView, which is itself sized to the guest aspect ratio (see
 * {@code VMNativeDisplayActivity#updateAspectRatio}), so its bounds carry no letterbox/pillarbox
 * bars: there is no offset and scale is simply guest/view per axis.
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

    public static TouchTransform compute(int guestWidth, int guestHeight,
                                         int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || guestWidth <= 0 || guestHeight <= 0) {
            return new TouchTransform(1f, 1f);
        }
        return new TouchTransform((float) guestWidth / viewWidth,
            (float) guestHeight / viewHeight);
    }
}
