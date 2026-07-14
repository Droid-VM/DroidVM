package cn.classfun.droidvm.ui.vm.display.base;

import androidx.annotation.NonNull;

/**
 * Pluggable VM display source: the thing that puts guest pixels on screen. The display activities
 * only talk to this interface plus {@link DisplayViewportController}/{@link
 * DisplayChromeController}, so a new source (e.g. a zero-copy AHardwareBuffer shared straight
 * into the guest so it draws without a CPU copy) slots in without touching the console logic.
 *
 * Implementations today: {@code NativeSurfaceSource} (crosvm renders into an Android Surface via
 * the per-VM ICrosvmAndroidDisplayService) and {@code VncBitmapSource} (RFB framebuffer copies
 * into a bitmap-backed view).
 */
public interface DisplaySource {
    enum State {
        CONNECTING,
        CONNECTED,
        ERROR
    }

    interface Callbacks {
        /**
         * Guest/framebuffer resolution known or changed - feed
         * {@link DisplayViewportController#setContentSize}. In auto-resize mode this is also the
         * ack of a {@link #requestGuestResize} request.
         */
        void onContentSize(int width, int height);

        /** Connection-level state for the status bar / connecting overlay. */
        void onStateChanged(@NonNull State state);
    }

    /** Start producing frames. Idempotent; sources that connect on their own may no-op. */
    void start();

    /** Stop producing frames and release resources; the source is not reusable afterwards. */
    void shutdown();

    /**
     * Whether the guest display resolution can be changed at runtime (Auto-resize Guest
     * Display). Sources without a resize channel return false and {@link #requestGuestResize}
     * is a no-op; {@link DisplayViewportController#setAutoResize} must not be enabled for them.
     */
    boolean supportsGuestResize();

    /** Ask the guest display to switch to width x height; the ack arrives via onContentSize. */
    void requestGuestResize(int width, int height);
}
