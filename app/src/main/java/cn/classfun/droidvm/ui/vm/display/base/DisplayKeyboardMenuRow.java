// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButtonToggleGroup;

import cn.classfun.droidvm.R;

/**
 * Builds the keyboard row of the display fab-menu header: [extra keys on/off] and the
 * typing-surface selector [system IME | physical keyboard]. The surface group is
 * mutually exclusive with an off state (tap the selected one to dismiss both keyboards).
 * The system IME's checked state mirrors whether the IME is actually visible - the system owns
 * that surface (e.g. its collapse button dismisses it), so the row re-reads it on every open.
 */
public final class DisplayKeyboardMenuRow {
    public interface Host {
        boolean isExtraKeysVisible();

        void toggleExtraKeys();

        boolean isImeVisible();

        void showSystemKeyboard();

        void hideSystemKeyboard();

        boolean isPhyKeyboardVisible();

        void setPhyKeyboardVisible(boolean visible);
    }

    private DisplayKeyboardMenuRow() {
    }

    @NonNull
    public static View build(
        @NonNull LayoutInflater inflater, @NonNull Host host, @NonNull Runnable dismiss) {
        View row = inflater.inflate(R.layout.view_keyboard_mode_toggle, null);
        MaterialButtonToggleGroup extraGroup = row.findViewById(R.id.extra_toggle_group);
        MaterialButtonToggleGroup surfaceGroup = row.findViewById(R.id.surface_group);
        if (host.isExtraKeysVisible()) extraGroup.check(R.id.toggle_extra);
        if (host.isPhyKeyboardVisible()) surfaceGroup.check(R.id.mode_phy);
        else if (host.isImeVisible()) surfaceGroup.check(R.id.mode_sys);
        // The extra toggle stays open (it's an on/off switch, often combined with a surface
        // pick); surface selection dismisses so the summoned keyboard isn't covered.
        extraGroup.addOnButtonCheckedListener((g, id, checked) -> host.toggleExtraKeys());
        surfaceGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (id == R.id.mode_sys) {
                if (checked) {
                    host.setPhyKeyboardVisible(false);
                    host.showSystemKeyboard();
                } else {
                    host.hideSystemKeyboard();
                }
            } else if (id == R.id.mode_phy) {
                if (checked) {
                    host.hideSystemKeyboard();
                    host.setPhyKeyboardVisible(true);
                } else {
                    host.setPhyKeyboardVisible(false);
                }
            }
            if (checked) dismiss.run();
        });
        return row;
    }
}
