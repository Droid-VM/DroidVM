// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;

/**
 * The display fab menu's input-mode row, shared by both consoles: touch, tablet and mouse as one
 * segmented row, the active mode checked. The mouse button holds two modes, the relative
 * {@link InputMode#MOUSE} and the {@link InputMode#TRACKPAD}: from touch or tablet it selects the
 * mouse, and tapped again while either is active it switches to the other, its icon saying which.
 */
public final class InputModeToggle {
    private InputModeToggle() {
    }

    @NonNull
    public static View build(@NonNull LayoutInflater inflater, @NonNull InputMode current,
                             @NonNull Consumer<InputMode> select, @NonNull Runnable dismiss) {
        var group = (MaterialButtonToggleGroup) inflater.inflate(
            R.layout.view_input_mode_toggle, null);
        MaterialButton mouse = group.findViewById(R.id.mode_mouse);
        if (current == InputMode.TRACKPAD) {
            mouse.setIconResource(R.drawable.ic_touchpad);
            mouse.setContentDescription(inflater.getContext().getString(current.labelResId));
        }
        group.check(current.isVmPointer() ? R.id.mode_mouse
            : current == InputMode.TABLET ? R.id.mode_tablet : R.id.mode_touch);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            // The mouse button answers its clicks instead: a second tap on it checks nothing new.
            if (!isChecked || checkedId == R.id.mode_mouse) return;
            select.accept(checkedId == R.id.mode_tablet ? InputMode.TABLET : InputMode.TOUCH);
            dismiss.run();
        });
        mouse.setOnClickListener(v -> {
            select.accept(current == InputMode.MOUSE ? InputMode.TRACKPAD : InputMode.MOUSE);
            dismiss.run();
        });
        return group;
    }
}
