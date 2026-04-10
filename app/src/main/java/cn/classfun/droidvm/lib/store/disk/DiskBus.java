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
