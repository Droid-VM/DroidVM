package cn.classfun.droidvm.ui.vm.display.base;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;

/**
 * Pointer input mode shared by both display backends (VNC and native). The control bar exposes a
 * selector; each {@link DisplayInputBackend} interprets the current mode when it turns on-screen
 * touches into guest input:
 *
 * <ul>
 *   <li>{@link #TOUCH} — absolute multi-touch: fingers map straight to guest coordinates, multiple
 *       contacts preserved (the default; what a touchscreen guest expects).</li>
 *   <li>{@link #MOUSE} — relative pointer: drags become REL_X/REL_Y deltas with button/wheel
 *       emulation. Android has no absolute-mouse concept, so this is the only faithful mouse path
 *       for guests that want relative motion (FPS games, desktops with pointer acceleration).</li>
 *   <li>{@link #TABLET} — absolute single pointer (stylus/graphics-tablet): one contact mapped to
 *       absolute guest coordinates, no multi-finger gestures.</li>
 * </ul>
 *
 * The ordinal is persisted (SharedPreferences {@code display_input_mode}); TOUCH/MOUSE keep their
 * historical 0/1 values so existing settings stay valid, TABLET is appended as 2.
 */
public enum InputMode {
    TOUCH(R.string.vnc_menu_input_mode_touch),
    MOUSE(R.string.vnc_menu_input_mode_mouse),
    TABLET(R.string.vnc_menu_input_mode_tablet);

    @StringRes
    public final int labelResId;

    InputMode(@StringRes int labelResId) {
        this.labelResId = labelResId;
    }

    /** Persisted-ordinal lookup that never throws; unknown/out-of-range values fall back to TOUCH. */
    @NonNull
    public static InputMode fromOrdinal(int ordinal) {
        InputMode[] values = values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : TOUCH;
    }

    /** The next mode in the cycle, for a single toggle button that rotates TOUCH -> MOUSE -> TABLET. */
    @NonNull
    public InputMode next() {
        InputMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
