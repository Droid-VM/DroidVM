// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Single source of truth for the display chrome: which of toolbar / status bar / extra-keys panel
 * / physical keyboard / system bars are visible. Everything that used to flip view visibility
 * directly (fullscreen toggle, menu items, timers) mutates this state instead, and the one
 * {@link Host#applyChrome} callback writes the whole set atomically - so the pieces can never
 * drift apart.
 *
 * Rules:
 * <ul>
 *   <li>The physical keyboard is the "phy" typing surface; while it is up, the extra-keys panel
 *       hides its bottom laptop-common row (the host derives that from the phy flag).</li>
 *   <li>The extra-keys on/off is remembered separately per typing surface (with vs. without the
 *       physical keyboard), so e.g. landscape users can keep extra off only while phy is up.</li>
 *   <li>Fullscreen hides toolbar + status bar + system bars + extra keys + physical keyboard.
 *       The state before entering is remembered and restored on exit; toggles made while
 *       fullscreen show on top of it and are not persisted.</li>
 *   <li>No auto-hide: bars visibility is a pure function of this state. Transient system bars
 *       swiped in during fullscreen are the system's overlay and don't touch this state.</li>
 * </ul>
 */
public final class DisplayChromeController {
    public interface Host {
        /**
         * Write the whole chrome set: toolbar/status bar/system bars shown iff
         * {@code !fullscreen}; extra-keys panel shown iff {@code extraKeysVisible} (with its
         * laptop-common row hidden iff {@code phyKeyboardVisible}); physical keyboard shown iff
         * {@code phyKeyboardVisible}. The host should re-request insets after applying so the
         * display area updates in one pass.
         */
        void applyChrome(boolean fullscreen, boolean extraKeysVisible, boolean phyKeyboardVisible);
    }

    /** Persistence hook: fired after a (non-fullscreen) user action changes the remembered state. */
    public interface StateListener {
        void onUserStateChanged(boolean extraWithStrip, boolean extraWithPhy, boolean phyVisible);
    }

    private final Host host;
    @Nullable
    private StateListener stateListener;
    private boolean fullscreen;
    private boolean phyVisible;
    // Extra-keys visibility, remembered per typing surface.
    private boolean extraWithStrip;
    private boolean extraWithPhy;
    // Pre-fullscreen memory.
    private boolean savedPhy, savedExtraWithStrip, savedExtraWithPhy;

    public DisplayChromeController(
        boolean extraWithStrip, boolean extraWithPhy, boolean phyVisible, @NonNull Host host) {
        this.host = host;
        this.extraWithStrip = extraWithStrip;
        this.extraWithPhy = extraWithPhy;
        this.phyVisible = phyVisible;
    }

    public void setStateListener(@Nullable StateListener listener) {
        this.stateListener = listener;
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
            savedPhy = phyVisible;
            savedExtraWithStrip = extraWithStrip;
            savedExtraWithPhy = extraWithPhy;
            phyVisible = false;
            extraWithStrip = false;
            extraWithPhy = false;
        } else {
            phyVisible = savedPhy;
            extraWithStrip = savedExtraWithStrip;
            extraWithPhy = savedExtraWithPhy;
        }
        apply();
    }

    public void toggleExtraKeys() {
        if (phyVisible) extraWithPhy = !extraWithPhy;
        else extraWithStrip = !extraWithStrip;
        notifyState();
        apply();
    }

    public void setPhyKeyboardVisible(boolean visible) {
        if (phyVisible == visible) return;
        phyVisible = visible;
        notifyState();
        apply();
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public boolean isExtraKeysVisible() {
        return phyVisible ? extraWithPhy : extraWithStrip;
    }

    public boolean isPhyKeyboardVisible() {
        return phyVisible;
    }

    private void notifyState() {
        if (stateListener != null && !fullscreen)
            stateListener.onUserStateChanged(extraWithStrip, extraWithPhy, phyVisible);
    }

    private void apply() {
        host.applyChrome(fullscreen, isExtraKeysVisible(), phyVisible);
    }
}
