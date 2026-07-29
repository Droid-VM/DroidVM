// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Where an enabled display is presented -- the consumer side, as opposed to the producer
 * ({@link DisplayBackend}: virtio-gpu / simplefb).
 * <p>
 * UI only: this is never persisted under a key of its own, it is derived from and written back
 * to the existing {@code native_display_enabled} / {@code vnc_enabled} booleans.
 * <p>
 * The two are a single choice because crosvm makes them one: it builds a list of display
 * backends and keeps the first one that opens (the Android display service is inserted at the
 * front), so enabling both only ever gave you the native display with VNC silently dropped.
 */
public enum DisplayOutput implements StringEnum {
    // Unlike the NONE sentinels of the persisted enums, this one is a real, selectable choice:
    // display on with no output surface is a valid setup (crosvm falls back to its stub display),
    // so it carries a label instead of R.string.nullptr -- a nullptr entry reports
    // isDisplay() == false and EnumPicker.autoItems() would drop it from the picker.
    NONE(0, "none", R.string.create_vm_display_output_none),
    NATIVE(1, "native", R.string.create_vm_display_output_native),
    VNC(2, "vnc", R.string.create_vm_display_output_vnc);

    private final int value;
    private final String name;
    private final @StringRes int stringId;

    DisplayOutput(int value, String name, @StringRes int stringId) {
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
