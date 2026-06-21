package cn.classfun.droidvm.lib.store.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum Ipv6Source {
    STATIC("static"),
    DHCP_PD("dhcp_pd");

    private final String key;

    Ipv6Source(@NonNull String key) {
        this.key = key;
    }

    @NonNull
    public String key() {
        return key;
    }

    @NonNull
    public static Ipv6Source fromKey(@Nullable String key) {
        for (var v : values())
            if (v.key.equals(key)) return v;
        return STATIC;
    }
}
