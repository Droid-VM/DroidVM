package cn.classfun.droidvm.lib.store.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum UplinkMode {
    L2("l2"),
    L3("l3");

    private final String key;

    UplinkMode(@NonNull String key) {
        this.key = key;
    }

    @NonNull
    public String key() {
        return key;
    }

    @NonNull
    public static UplinkMode fromKey(@Nullable String key) {
        for (var v : values())
            if (v.key.equals(key)) return v;
        // legacy/unknown keys (incl. the dropped "none" mode) read as host L3
        return L3;
    }
}
