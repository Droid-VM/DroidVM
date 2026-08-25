// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Who consumes one screen's frames -- the export side of a {@link VMScreenConfig} binding.
 * <p>
 * A screen drives at most one exporter. That is a decision, not a limitation waiting to be
 * lifted: the alternative to the old silent race (two sinks configured, VNC wins, the app's
 * Surface never gets a binder) was either mirroring or an error, and this picks the error.
 * crosvm enforces the same rule on its side and refuses to start a VM with two exporters on
 * one screen, so the editor must never write one.
 * <p>
 * The names are persisted, so they are the stable part; they say nothing about which screen
 * the binding is on, because that is the key the binding is stored under.
 */
public enum DisplayExporter implements StringEnum {
    // Unlike the NONE sentinels of the other persisted enums, this one is a real, selectable
    // choice: a screen nobody is watching is a state, not a fault -- crosvm accepts it too. So
    // it carries a label instead of R.string.nullptr; a nullptr entry reports isDisplay() ==
    // false and EnumPicker.autoItems() would drop it from the picker.
    NONE(0, "none", R.string.create_vm_screen_exporter_none),
    NATIVE(1, "native", R.string.create_vm_screen_exporter_native),
    VNC(2, "vnc", R.string.create_vm_screen_exporter_vnc);

    private final int value;
    private final String name;
    private final @StringRes int stringId;

    DisplayExporter(int value, String name, @StringRes int stringId) {
        this.value = value;
        this.name = name;
        this.stringId = stringId;
    }

    @SuppressWarnings("unused")
    public int getValue() {
        return value;
    }

    @SuppressWarnings("unused")
    public String getName() {
        return name;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }

    @Override
    public boolean isDisplay() {
        return stringId != R.string.nullptr;
    }
}
