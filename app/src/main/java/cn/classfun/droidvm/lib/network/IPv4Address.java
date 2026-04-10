package cn.classfun.droidvm.lib.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

@SuppressWarnings({"unused", "BooleanMethodIsAlwaysInverted"})
public final class IPv4Address
    extends IPAddress<Long, IPv4Address, IPv4Network>
    implements Comparable<IPv4Address> {
    private final long value;

    public IPv4Address(long value) {
        this.value = value & 0xFFFFFFFFL;
    }

    public IPv4Address(int v1, int v2, int v3, int v4) {
        this.value = ((v1 & 0xFFL) << 24) |
            ((v2 & 0xFFL) << 16) |
            ((v3 & 0xFFL) << 8) |
            (v4 & 0xFFL);
    }

    @Override
    public int version() {
        return 4;
    }

    @NonNull
    @Override
    public byte[] bytes() {
        return new byte[]{
            (byte) ((value >> 24) & 0xFF),
            (byte) ((value >> 16) & 0xFF),
            (byte) ((value >> 8) & 0xFF),
            (byte) (value & 0xFF)
        };
    }

    public Long value() {
        return value;
    }

    @NonNull
    public static IPv4Address parse(@Nullable String ip) {
        if (ip == null || ip.isEmpty())
            throw new IllegalArgumentException("IP address string cannot be null or empty");
        var parts = ip.split("\\.", -1);
        if (parts.length != 4)
            throw new IllegalArgumentException(fmt("Invalid IPv4 address format: %s", ip));
        long result = 0;
        for (var part : parts) {
            int octet;
            octet = Integer.parseInt(part);
            if (octet < 0 || octet > 255)
                throw new IllegalArgumentException(fmt("Invalid octet in IPv4 address: %s", part));
            result = (result << 8) | octet;
        }
        return new IPv4Address(result);
    }

    public static boolean isValid(@Nullable String val) {
        try {
            parse(val);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @NonNull
    @Override
    public String toString() {
        var b = bytes();
        return fmt("%d.%d.%d.%d",
            b[0] & 0xFF, b[1] & 0xFF,
            b[2] & 0xFF, b[3] & 0xFF
        );
    }

    @NonNull
    @Override
    public IPv4Address add(Long delta) {
        return new IPv4Address(value + delta);
    }

    @NonNull
    @Override
    public IPv4Address add(int delta) {
        return new IPv4Address(value + delta);
    }

    @Override
    public int compareTo(@NonNull IPv4Address other) {
        return Long.compare(this.value, other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IPv4Address)) return false;
        return value == ((IPv4Address) o).value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }
}
