package cn.classfun.droidvm.lib.network;

import static cn.classfun.droidvm.lib.network.IPv6Address.MAX_VALUE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigInteger;

@SuppressWarnings({"unused", "BooleanMethodIsAlwaysInverted"})
public final class IPv6Network extends IPNetwork<BigInteger, IPv6Address, IPv6Network> {
    private final IPv6Address address;
    private final int prefix;

    public IPv6Network(@NonNull IPv6Address address, int prefix) {
        this.address = address;
        this.prefix = prefix;
    }

    @Override
    public int version() {
        return 6;
    }

    @NonNull
    public IPv6Address address() {
        return address;
    }

    public int prefix() {
        return prefix;
    }

    @NonNull
    public BigInteger mask() {
        if (prefix == 0) return BigInteger.ZERO;
        return MAX_VALUE.shiftLeft(128 - prefix).and(MAX_VALUE);
    }

    @NonNull
    public IPv6Address networkAddress() {
        return new IPv6Address(address.value().and(mask()));
    }

    @NonNull
    @Override
    public IPv6Address broadcastAddress() {
        return new IPv6Address(networkAddress().value().or(MAX_VALUE.xor(mask())));
    }

    @Override
    public BigInteger totalAddresses() {
        if (prefix == 128) return BigInteger.ONE;
        return BigInteger.ONE.shiftLeft(128 - prefix);
    }

    @Nullable
    @Override
    public IPv6Address firstUsable() {
        if (prefix == 128) return networkAddress();
        var net = networkAddress().value();
        var first = net.add(BigInteger.ONE);
        if (first.compareTo(lastAddress().value()) > 0) return null;
        return new IPv6Address(first);
    }

    @Nullable
    @Override
    public IPv6Address lastUsable() {
        if (prefix == 128) return networkAddress();
        var last = lastAddress().value();
        var beforeLast = last.subtract(BigInteger.ONE);
        if (beforeLast.compareTo(networkAddress().value()) < 0) return null;
        return new IPv6Address(beforeLast);
    }

    @NonNull
    public IPv6Address lastAddress() {
        var hostMask = MAX_VALUE.xor(mask());
        return new IPv6Address(networkAddress().value().or(hostMask));
    }

    @Override
    public boolean contains(@NonNull IPv6Address ip) {
        var v = ip.value();
        return v.compareTo(networkAddress().value()) >= 0
            && v.compareTo(lastAddress().value()) <= 0;
    }

    @Override
    public boolean overlaps(@NonNull IPv6Network other) {
        var thisNet = this.networkAddress().value();
        var thisLast = this.lastAddress().value();
        var otherNet = other.networkAddress().value();
        var otherLast = other.lastAddress().value();
        return thisNet.compareTo(otherLast) <= 0
            && otherNet.compareTo(thisLast) <= 0;
    }

    @NonNull
    @Override
    public String toString() {
        return fmt("%s/%d", address, prefix);
    }

    @NonNull
    @Override
    public String toNetworkString() {
        return fmt("%s/%d", networkAddress(), prefix);
    }

    @NonNull
    public static IPv6Network parse(@Nullable String cidr) {
        if (cidr == null || cidr.isEmpty())
            throw new IllegalArgumentException("CIDR string cannot be null or empty");
        int slash = cidr.lastIndexOf('/');
        if (slash < 0)
            throw new IllegalArgumentException("CIDR string must contain '/' separating address and prefix");
        var addr = IPv6Address.parse(cidr.substring(0, slash));
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        if (prefix < 0 || prefix > 128)
            throw new IllegalArgumentException(fmt("Invalid prefix length: %d", prefix));
        return new IPv6Network(addr, prefix);
    }

    public static boolean isValid(@Nullable String val) {
        try {
            parse(val);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IPv6Network)) return false;
        var that = (IPv6Network) o;
        return prefix == that.prefix && address.equals(that.address);
    }

    @Override
    public int hashCode() {
        return 31 * address.hashCode() + prefix;
    }
}
