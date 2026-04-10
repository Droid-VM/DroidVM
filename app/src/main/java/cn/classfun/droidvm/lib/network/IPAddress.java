package cn.classfun.droidvm.lib.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

public abstract class IPAddress<V, A extends IPAddress<V, A, N>, N extends IPNetwork<V, A, N>> {
    @SuppressWarnings("unused")
    public abstract int version();

    @SuppressWarnings("unused")
    public abstract byte[] bytes();

    @SuppressWarnings("unused")
    public abstract V value();

    @SuppressWarnings("unused")
    public abstract A add(V delta);

    @SuppressWarnings("unused")
    public abstract A add(int delta);

    @NonNull
    @SuppressWarnings("unused")
    public static IPAddress<?, ?, ?> parse(@NonNull String ip) {
        try {
            return IPv4Address.parse(ip);
        } catch (Exception ignored) {
        }
        try {
            return IPv6Address.parse(ip);
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException(fmt("Invalid IP address: %s", ip));
    }
}
