// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Single source of truth for the display chrome: toolbar / status bar / system bars, and which
 * parts of the on-screen keyboard are up. Everything that used to flip view visibility directly
 * (fullscreen toggle, menu items, keyboard buttons) mutates this state instead, and the one
 * {@link Host#applyChrome} callback writes the whole set atomically - so the pieces can never
 * drift apart.
 *
 * Rules:
 * <ul>
 *   <li>The keyboard mode picks the Main zone: nothing, the one-row companion to the system IME,
 *       or the laptop keyboard.</li>
 *   <li>The Extra zone is the point of the system-IME mode - the keys an IME cannot send - so
 *       it is always up there; only the laptop mode, whose Main block already carries most of
 *       them, lets it be toggled off. FNx is a toggle in both. Both keep their values through
 *       {@link KeyboardMode#NONE}: hiding the keyboard is not the same as turning its zones
 *       off.</li>
 *   <li>Fullscreen hides toolbar + status bar + system bars + the whole keyboard. The state
 *       before entering is remembered and restored on exit; changes made while fullscreen show
 *       on top of it and are not persisted.</li>
 *   <li>No auto-hide: bar visibility is a pure function of this state. Transient system bars
 *       swiped in during fullscreen are the system's overlay and don't touch it.</li>
 * </ul>
 */
public final class DisplayChromeController {
    public interface Host {
        /**
         * Write the whole chrome set: toolbar/status bar/system bars shown iff
         * {@code !fullscreen}; the keyboard's Main zone per {@code mode}; the Extra and FNx
         * zones shown iff their flag is set and {@code mode} shows a keyboard at all. The host
         * should re-request insets after applying so the display area updates in one pass.
         */
        void applyChrome(
            boolean fullscreen,
            @NonNull KeyboardMode mode,
            boolean extraVisible,
            boolean fnxVisible);
    }

    /** Persistence hook: fired after a (non-fullscreen) user action changes the state. */
    public interface StateListener {
        void onUserStateChanged(
            @NonNull KeyboardMode mode, boolean extraVisible, boolean fnxVisible);
    }

    private final Host host;
    @Nullable
    private StateListener stateListener;
    private boolean fullscreen;
    private KeyboardMode mode;
    private boolean extraVisible;
    private boolean fnxVisible;
    // Pre-fullscreen memory.
    private KeyboardMode savedMode;
    private boolean savedExtra, savedFnx;

    public DisplayChromeController(
        @NonNull KeyboardMode mode,
        boolean extraVisible,
        boolean fnxVisible,
        @NonNull Host host
    ) {
        this.host = host;
        this.mode = mode;
        this.extraVisible = extraVisible;
        this.fnxVisible = fnxVisible;
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
            savedMode = mode;
            savedExtra = extraVisible;
            savedFnx = fnxVisible;
            mode = KeyboardMode.NONE;
        } else {
            mode = savedMode == null ? KeyboardMode.NONE : savedMode;
            extraVisible = savedExtra;
            fnxVisible = savedFnx;
        }
        apply();
    }

    public void setKeyboardMode(@NonNull KeyboardMode newMode) {
        if (mode == newMode) return;
        mode = newMode;
        notifyState();
        apply();
    }

    public void toggleExtraZone() {
        extraVisible = !extraVisible;
        notifyState();
        apply();
    }

    public void toggleFnxZone() {
        fnxVisible = !fnxVisible;
        notifyState();
        apply();
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    @NonNull
    public KeyboardMode getKeyboardMode() {
        return mode;
    }

    public boolean isExtraVisible() {
        return extraVisible;
    }

    public boolean isFnxVisible() {
        return fnxVisible;
    }

    private void notifyState() {
        if (stateListener != null && !fullscreen)
            stateListener.onUserStateChanged(mode, extraVisible, fnxVisible);
    }

    private void apply() {
        boolean showing = mode.showsKeyboard();
        boolean extra = mode == KeyboardMode.SYSTEM || (showing && extraVisible);
        host.applyChrome(fullscreen, mode, extra, showing && fnxVisible);
    }
}
