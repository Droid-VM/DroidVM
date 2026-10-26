// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * How the guest finds its virtio devices: on a PCI bus, or as virtio-mmio windows in its device
 * tree, for a guest with a virtio-mmio driver and no PCI host driver. A property of the guest, so
 * it is the VM's and not a device's: crosvm moves every disk, input device, network card and the
 * GPU together. crosvm only, and aarch64 only.
 */
public enum VirtioTransport implements StringEnum {
    PCI(R.string.create_vm_virtio_transport_pci),
    MMIO(R.string.create_vm_virtio_transport_mmio);

    public static final String KEY = "virtio_transport";
    // A config from before this key is a PCI VM: what crosvm did with no flag.
    public static final VirtioTransport DEFAULT = PCI;

    private final @StringRes int stringId;

    VirtioTransport(@StringRes int stringId) {
        this.stringId = stringId;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }

    @NonNull
    public static VirtioTransport fromItem(@NonNull DataItem item) {
        return optEnum(item, KEY, DEFAULT);
    }
}
