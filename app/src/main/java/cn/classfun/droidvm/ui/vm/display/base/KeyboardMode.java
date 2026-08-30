// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;

/**
 * Which typing surface the display shows. Exactly one is active, and it decides what the Main
 * zone of the on-screen keyboard is; the Extra and FNx zones are independent toggles that only
 * apply while a keyboard is up ({@link #NONE} hides everything without forgetting them).
 */
public enum KeyboardMode {
    /** Nothing shown - the display gets the whole screen. */
    NONE(R.string.keyboard_mode_none),
    /** Main is one row of the keys a soft keyboard lacks; text comes from the system IME. */
    SYSTEM(R.string.keyboard_mode_system),
    /** Main is the on-screen laptop keyboard; no IME involved. */
    LAPTOP(R.string.keyboard_mode_laptop);

    private final @StringRes int stringId;

    KeyboardMode(@StringRes int stringId) {
        this.stringId = stringId;
    }

    @StringRes
    public int getStringId() {
        return stringId;
    }

    public boolean showsKeyboard() {
        return this != NONE;
    }

    @NonNull
    public static KeyboardMode fromName(@Nullable String name) {
        if (name != null) {
            for (var m : values())
                if (m.name().equals(name)) return m;
        }
        return SYSTEM;
    }
}
