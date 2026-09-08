// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import android.content.Context;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;
import cn.classfun.droidvm.lib.store.vm.VMXhciConfig;

/**
 * How many root ports one xHCI controller offers, as the picker's vocabulary.
 *
 * <p>An enum only because {@link cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget} takes
 * one, and the card's bottom row is the VirtIO Sound row -- the same widget, the same metrics.
 * Nothing stores it: {@link VMXhciConfig#MIN_PORTS}..{@link VMXhciConfig#MAX_PORTS} is a number
 * in the config, and this is only how the number is picked. So the constants carry the count
 * rather than a name that would then have to mean one.</p>
 *
 * <p>No value is ever refused, on any backend: crosvm ignores anything but 8, and the card says
 * so in red instead. Refusing here is what {@code setDisabledItems} would do, and it would also
 * move the stored value -- the opposite of "pick it, and be told what the VM will make of it".</p>
 */
public enum XhciPortCount implements StringEnum {
    P0, P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15;

    /** The count this constant stands for. */
    public int ports() {
        return ordinal();
    }

    /** The constant for a stored count, clamped the way the config clamps it. */
    @NonNull
    public static XhciPortCount of(int ports) {
        return values()[VMXhciConfig.clampPorts(ports)];
    }

    @NonNull
    @Override
    public String getDisplayString(@NonNull Context context) {
        return context.getString(R.string.edit_vm_xhci_port_item, ports());
    }
}
