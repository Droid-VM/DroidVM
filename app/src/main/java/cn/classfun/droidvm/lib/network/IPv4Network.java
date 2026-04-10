package cn.classfun.droidvm.lib.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

@SuppressWarnings({"unused", "BooleanMethodIsAlwaysInverted"})
public final class IPv4Network extends IPNetwork<Long, IPv4Address, IPv4Network> {
    private final IPv4Address address;
    private final int prefix;

    public IPv4Network(@NonNull IPv4Address address, int prefix) {
        this.address = address;
        this.prefix = prefix;
    }

    public IPv4Network(long address, int prefix) {
        this(new IPv4Address(address), prefix);
    }

    @Override
    public int version() {
        return 4;
    }

    @NonNull
    @Override
    public IPv4Address address() {
        return address;
    }

    @NonNull
    public IPv4Network network() {
        return new IPv4Network(networkAddress(), prefix);
    }

    @Override
    public int prefix() {
        return prefix;
    }

    @NonNull
    @Override
    public Long mask() {
        if (prefix == 0) return 0L;
        return (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    @NonNull
    @Override
    public IPv4Address networkAddress() {
        return new IPv4Address(address.value() & mask());
    }

    @NonNull
    @Override
    public IPv4Address broadcastAddress() {
        return new IPv4Address(networkAddress().value() | (~mask() & 0xFFFFFFFFL));
    }

    @NonNull
    @Override
    public Long totalAddresses() {
        return 1L << (32 - prefix);
    }

    @Nullable
    @Override
    public IPv4Address firstUsable() {
        if (prefix >= 31) return null;
        return networkAddress().add(1);
    }

    @Nullable
    @Override
    public IPv4Address lastUsable() {
        if (prefix >= 31) return null;
        return broadcastAddress().add(-1);
    }

    @Override
    public boolean contains(@NonNull IPv4Address ip) {
        long v = ip.value();
        return v >= networkAddress().value()
            && v <= broadcastAddress().value();
    }

    @Override
    public boolean overlaps(@NonNull IPv4Network other) {
        long thisNet = this.networkAddress().value();
        long thisBcast = this.broadcastAddress().value();
        long otherNet = other.networkAddress().value();
        long otherBcast = other.broadcastAddress().value();
        return thisNet <= otherBcast && otherNet <= thisBcast;
    }

    @Nullable
    public IPv4Address dhcpPoolStart() {
        if (prefix >= 31) return null;
        long start = networkAddress().value() + 2;
        if (start >= broadcastAddress().value()) return null;
        return new IPv4Address(start);
    }

    @Nullable
    public IPv4Address dhcpPoolEnd() {
        if (prefix >= 31) return null;
        long end = broadcastAddress().value() - 1;
        if (end <= networkAddress().value() + 1) return null;
        return new IPv4Address(end);
    }

    @NonNull
    @Override
    public String toString() {
        return fmt("%s/%d", address.toString(), prefix);
    }

    @NonNull
    @Override
    public String toNetworkString() {
        return fmt("%s/%d", networkAddress(), prefix);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IPv4Network)) return false;
        var that = (IPv4Network) o;
        return prefix == that.prefix && address.equals(that.address);
    }

    @Override
    public int hashCode() {
        return 31 * address.hashCode() + prefix;
    }

    @NonNull
    public static IPv4Network parse(@Nullable String cidr) {
        if (cidr == null || cidr.isEmpty())
            throw new IllegalArgumentException("CIDR string cannot be null or empty");
        int slash = cidr.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("CIDR string must contain '/'");
        var addr = IPv4Address.parse(cidr.substring(0, slash));
        int prefix = Integer.parseInt(cidr.substring(slash + 1));
        if (prefix < 0 || prefix > 32)
            throw new IllegalArgumentException(fmt("Invalid prefix length: %d", prefix));
        return new IPv4Network(addr, prefix);
    }

    public static boolean isValid(@Nullable String val) {
        try {
            parse(val);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
