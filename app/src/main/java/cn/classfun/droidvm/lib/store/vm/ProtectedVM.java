// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.Enums;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum ProtectedVM implements StringEnum {
    PROTECTED_NORMAL(0, R.string.create_vm_protected_normal),
    PROTECTED_PROTECTED(1, R.string.create_vm_protected_protected),
    PROTECTED_WITHOUT_FIRMWARE(2, R.string.create_vm_protected_without_firmware),
    /**
     * Protected as far as the hypervisor is concerned, but the guest's RAM is SHARE'd to it at
     * run time instead of lent before boot, so the host can still reach it. Gunyah only.
     *
     * <p>The difference the user sees is which guests boot: a protected VM needs a kernel built
     * with {@code CONFIG_RESTRICTED_DMA_POOL}, because its memory is lent and every virtio buffer
     * has to travel through a bounce pool. No distribution builds that. Here there is nothing to
     * bounce through, so a stock distribution kernel boots -- which is why this mode is not one
     * of the two the boot tab warns about.
     */
    PSEUDO_UNPROTECTED(3, R.string.create_vm_protected_pseudo_unprotected);

    /** The config key every reader of this enum uses. */
    public static final String KEY = "protected_vm";

    /**
     * Is this VM's guest memory lent to the hypervisor -- i.e. out of the host's reach while it
     * runs? That is the property two guest-side limits follow from: virtio has to bounce through
     * a restricted DMA pool (so a kernel without {@code CONFIG_DMA_RESTRICTED_POOL} finds no
     * devices), and USB passthrough has nowhere to map the transfer buffers.
     *
     * <p>{@link #PSEUDO_UNPROTECTED} is deliberately false here: it is protected to the
     * hypervisor, but its RAM is SHARE'd at run time rather than lent, so the host can still
     * reach it and both limits above go away. That is what makes it the offer when either one
     * bites.</p>
     */
    public static boolean lendsGuestMemory(@NonNull DataItem item) {
        var mode = Enums.optEnum(item, KEY, PROTECTED_WITHOUT_FIRMWARE);
        return mode == PROTECTED_PROTECTED || mode == PROTECTED_WITHOUT_FIRMWARE;
    }

    private final int value;
    private final @StringRes int stringId;

    ProtectedVM(int value, @StringRes int stringId) {
        this.value = value;
        this.stringId = stringId;
    }

    @SuppressWarnings("unused")
    public int getValue() {
        return value;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }
}
