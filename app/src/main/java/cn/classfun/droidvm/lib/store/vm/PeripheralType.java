// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Kind of virtual peripheral attached to a VM. Every entry of a VM config's "peripherals" array
 * carries one of these in its {@code type} key; the backends turn them into device flags.
 *
 * <p>Only audio endpoints exist so far -- both map onto one virtio-snd card, a SPEAKER becoming
 * an output PCM device and a MICROPHONE an input one. New kinds can be added here without
 * touching the list UI, which is driven entirely off this enum.</p>
 */
public enum PeripheralType implements StringEnum {
    SPEAKER(R.string.edit_vm_peripheral_type_speaker, R.drawable.ic_speaker),
    MICROPHONE(R.string.edit_vm_peripheral_type_microphone, R.drawable.ic_microphone);

    private final @StringRes int titleId;
    private final @DrawableRes int iconId;

    PeripheralType(@StringRes int titleId, @DrawableRes int iconId) {
        this.titleId = titleId;
        this.iconId = iconId;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    @DrawableRes
    public int getIconId() {
        return iconId;
    }

    /** True when the host device feeds the guest (capture) rather than the other way round. */
    public boolean isInput() {
        return this == MICROPHONE;
    }

    /** True when this kind needs the host's RECORD_AUDIO permission to have any chance of working. */
    public boolean needsRecordPermission() {
        return isInput();
    }
}
