package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum PortProtocol implements StringEnum {
    TCP,
    UDP;

    @NonNull
    @Override
    public String getString() {
        return name();
    }
}
