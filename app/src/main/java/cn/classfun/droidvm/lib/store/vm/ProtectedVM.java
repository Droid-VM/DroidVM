package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum ProtectedVM implements StringEnum {
    PROTECTED_NORMAL(0, R.string.create_vm_protected_normal),
    PROTECTED_PROTECTED(1, R.string.create_vm_protected_protected),
    PROTECTED_WITHOUT_FIRMWARE(2, R.string.create_vm_protected_without_firmware);

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
