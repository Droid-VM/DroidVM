package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum VMBackend implements StringEnum {
    CROSVM(R.string.create_vm_backend_crosvm),
    QEMU(R.string.create_vm_backend_qemu);

    public static final VMBackend DEFAULT = CROSVM;

    private final @StringRes int stringId;

    VMBackend(@StringRes int stringId) {
        this.stringId = stringId;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }
}

