// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.disk;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum DiskBus implements StringEnum {
    VIRTIO,
    SCSI,
    PMEM,
    PFLASH,
    CDROM;

    @NonNull
    @Override
    public String getString() {
        return name();
    }
}
