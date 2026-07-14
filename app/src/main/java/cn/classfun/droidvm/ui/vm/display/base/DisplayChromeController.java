package cn.classfun.droidvm.ui.vm.display.base;

import androidx.annotation.NonNull;

/**
 * Single source of truth for the display chrome: which of toolbar / status bar / extra-keys panel
 * / system bars are visible. Everything that used to flip view visibility directly (fullscreen
 * toggle, menu items, timers) mutates this state instead, and the one {@link Host#applyChrome}
 * callback writes the whole set atomically - so the pieces can never drift apart.
 *
 * Rules:
 * <ul>
 *   <li>Fullscreen hides toolbar + status bar + system bars + extra keys. The extra-keys state
 *       before entering is remembered and restored on exit.</li>
 *   <li>The extra-keys panel can still be toggled from the menu while fullscreen (it then shows
 *       on top of the fullscreen display).</li>
 *   <li>No auto-hide: bars visibility is a pure function of this state. Transient system bars
 *       swiped in during fullscreen are the system's overlay and don't touch this state.</li>
 * </ul>
 */
public final class DisplayChromeController {
    public interface Host {
        /**
         * Write the whole chrome set: toolbar/status bar/system bars shown iff
         * {@code !fullscreen}; extra-keys panel shown iff {@code extraKeysVisible}. The host
         * should re-request insets after applying so the display area updates in one pass.
         */
        void applyChrome(boolean fullscreen, boolean extraKeysVisible);
    }

    private final Host host;
    private boolean fullscreen;
    private boolean extraKeysVisible;
    private boolean extraKeysBeforeFullscreen;

    public DisplayChromeController(boolean extraKeysVisibleInitially, @NonNull Host host) {
        this.host = host;
        this.extraKeysVisible = extraKeysVisibleInitially;
    }

    /** Push the initial state to the host once its views are ready. */
    public void applyInitial() {
        apply();
    }

    public void toggleFullscreen() {
        setFullscreen(!fullscreen);
    }

    public void setFullscreen(boolean enabled) {
        if (fullscreen == enabled) {
            return;
        }
        fullscreen = enabled;
        if (enabled) {
            extraKeysBeforeFullscreen = extraKeysVisible;
            extraKeysVisible = false;
        } else {
            extraKeysVisible = extraKeysBeforeFullscreen;
        }
        apply();
    }

    public void toggleExtraKeys() {
        extraKeysVisible = !extraKeysVisible;
        apply();
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public boolean isExtraKeysVisible() {
        return extraKeysVisible;
    }

    private void apply() {
        host.applyChrome(fullscreen, extraKeysVisible);
    }
}
