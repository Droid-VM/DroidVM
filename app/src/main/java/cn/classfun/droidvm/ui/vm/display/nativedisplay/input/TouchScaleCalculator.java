package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

/**
 * Computes per-axis touch scale (and letterbox/pillarbox offsets) from view size to guest
 * resolution. When the display SurfaceView is already sized to the guest aspect ratio, offsets are
 * zero and scale is simply guest/view.
 */
public final class TouchScaleCalculator {
    private TouchScaleCalculator() {
    }

    public static final class TouchTransform {
        public final float scaleX;
        public final float scaleY;
        public final float offsetX;
        public final float offsetY;

        TouchTransform(float scaleX, float scaleY, float offsetX, float offsetY) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }

    public static TouchTransform compute(int guestWidth, int guestHeight,
                                         int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || guestWidth <= 0 || guestHeight <= 0) {
            return new TouchTransform(1f, 1f, 0f, 0f);
        }
        float guestRatio = (float) guestWidth / guestHeight;
        float viewRatio = (float) viewWidth / viewHeight;
        if (viewRatio > guestRatio) {
            // Pillarbox: bars on left/right
            float displayHeight = viewHeight;
            float displayWidth = displayHeight * guestRatio;
            float offsetX = (viewWidth - displayWidth) / 2f;
            return new TouchTransform(guestWidth / displayWidth, guestHeight / displayHeight,
                offsetX, 0f);
        } else {
            // Letterbox: bars on top/bottom
            float displayWidth = viewWidth;
            float displayHeight = displayWidth / guestRatio;
            float offsetY = (viewHeight - displayHeight) / 2f;
            return new TouchTransform(guestWidth / displayWidth, guestHeight / displayHeight,
                0f, offsetY);
        }
    }
}
