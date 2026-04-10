package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum SharedDirType implements StringEnum {
    P9,
    FS;

    @NonNull
    @Override
    public String getString() {
        return name();
    }
}
