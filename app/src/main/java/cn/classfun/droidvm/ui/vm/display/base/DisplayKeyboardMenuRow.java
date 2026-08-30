// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;

/**
 * The keyboard row of the display fab-menu header: pick the typing surface - none, the system
 * IME plus its companion row, or the laptop keyboard. Only the surface is chosen here; the
 * Extra and FNx zones are toggled from keys on the keyboard itself, where they are visible.
 */
public final class DisplayKeyboardMenuRow {
    private DisplayKeyboardMenuRow() {
    }

    @NonNull
    public static View build(
        @NonNull LayoutInflater inflater,
        @NonNull KeyboardMode current,
        @NonNull Consumer<KeyboardMode> onPick,
        @NonNull Runnable dismiss
    ) {
        var group = (MaterialButtonToggleGroup)
            inflater.inflate(R.layout.view_keyboard_mode_toggle, null);
        group.check(buttonFor(current));
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            onPick.accept(modeFor(checkedId));
            dismiss.run();
        });
        return group;
    }

    private static int buttonFor(@NonNull KeyboardMode mode) {
        switch (mode) {
            case NONE:
                return R.id.mode_kb_none;
            case LAPTOP:
                return R.id.mode_kb_laptop;
            default:
                return R.id.mode_kb_system;
        }
    }

    @NonNull
    private static KeyboardMode modeFor(int buttonId) {
        if (buttonId == R.id.mode_kb_none) return KeyboardMode.NONE;
        if (buttonId == R.id.mode_kb_laptop) return KeyboardMode.LAPTOP;
        return KeyboardMode.SYSTEM;
    }
}
