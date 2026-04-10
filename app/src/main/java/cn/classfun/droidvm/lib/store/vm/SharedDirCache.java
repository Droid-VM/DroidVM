package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum SharedDirCache implements StringEnum {
    NEVER,
    AUTO,
    ALWAYS;

    @NonNull
    @Override
    public String getString() {
        return name();
    }
}
