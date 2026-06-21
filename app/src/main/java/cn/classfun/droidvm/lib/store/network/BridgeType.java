package cn.classfun.droidvm.lib.store.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum BridgeType {
    LINUX("linux"),
    GVISOR("gvisor");

    private final String key;

    BridgeType(@NonNull String key) {
        this.key = key;
    }

    @NonNull
    public String key() {
        return key;
    }

    @NonNull
    public static BridgeType fromKey(@Nullable String key) {
        for (var v : values())
            if (v.key.equals(key)) return v;
        return LINUX;
    }
}
