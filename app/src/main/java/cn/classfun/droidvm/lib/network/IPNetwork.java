package cn.classfun.droidvm.lib.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

public abstract class IPNetwork<V, A extends IPAddress<V, A, N>, N extends IPNetwork<V, A, N>> {
    @SuppressWarnings("unused")
    public abstract int version();

    @SuppressWarnings("unused")
    public abstract A address();

    @SuppressWarnings("unused")
    public abstract int prefix();

    @SuppressWarnings("unused")
    public abstract V mask();

    @SuppressWarnings("unused")
    public abstract A networkAddress();

    @SuppressWarnings("unused")
    public abstract A broadcastAddress();

    @SuppressWarnings("unused")
    public abstract V totalAddresses();

    @SuppressWarnings("unused")
    public abstract A firstUsable();

    @SuppressWarnings("unused")
    public abstract A lastUsable();

    @SuppressWarnings("unused")
    public abstract boolean contains(@NonNull A ip);

    @SuppressWarnings("unused")
    public abstract boolean overlaps(@NonNull N other);

    @SuppressWarnings("unused")
    public abstract String toNetworkString();

    @NonNull
    @SuppressWarnings("unused")
    public static IPNetwork<?, ?, ?> parse(@NonNull String ip) {
        try {
            return IPv4Network.parse(ip);
        } catch (Exception ignored) {
        }
        try {
            return IPv6Network.parse(ip);
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException(fmt("Invalid IP network: %s", ip));
    }
}
