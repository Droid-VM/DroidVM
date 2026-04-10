package cn.classfun.droidvm.lib.network;

import static java.lang.System.arraycopy;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;

public final class IPv6Address
    extends IPAddress<BigInteger, IPv6Address, IPv6Network>
    implements Comparable<IPv6Address> {
    static final BigInteger TWO_128 = BigInteger.ONE.shiftLeft(128);
    static final BigInteger MAX_VALUE = TWO_128.subtract(BigInteger.ONE);
    private final BigInteger value;

    public IPv6Address(@NonNull BigInteger value) {
        this.value = value.and(MAX_VALUE);
    }

    @Override
    public int version() {
        return 6;
    }

    @Override
    public byte[] bytes() {
        return value.toByteArray();
    }

    @NonNull
    public BigInteger value() {
        return value;
    }

    @NonNull
    public static IPv6Address parse(@Nullable String addr) {
        if (addr == null || addr.isEmpty())
            throw new IllegalArgumentException("Address string cannot be null or empty");
        int pct = addr.indexOf('%');
        if (pct >= 0) addr = addr.substring(0, pct);
        if (addr.startsWith("[") && addr.endsWith("]"))
            addr = addr.substring(1, addr.length() - 1);
        try {
            var inet = InetAddress.getByName(addr);
            if (!(inet instanceof Inet6Address))
                throw new IllegalArgumentException(fmt("Not a valid IPv6 address: %s", addr));
            byte[] bytes = inet.getAddress();
            return new IPv6Address(new BigInteger(1, bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    @Override
    public String toString() {
        return format(value);
    }

    @NonNull
    static String format(@NonNull BigInteger ip) {
        ip = ip.and(MAX_VALUE);
        byte[] full = ip.toByteArray();
        byte[] bytes = new byte[16];
        if (full.length <= 16) {
            arraycopy(full, 0, bytes, 16 - full.length, full.length);
        } else {
            arraycopy(full, full.length - 16, bytes, 0, 16);
        }
        try {
            var inet = Inet6Address.getByAddress(bytes);
            var s = inet.getHostAddress();
            int pct = s != null ? s.indexOf('%') : -1;
            if (pct >= 0) s = s.substring(0, pct);
            return s != null ? s : "::";
        } catch (Exception e) {
            var sb = new StringBuilder();
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) sb.append(':');
                var num = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
                sb.append(fmt("%x", num));
            }
            return sb.toString();
        }
    }

    @NonNull
    public IPv6Address add(@NonNull BigInteger delta) {
        return new IPv6Address(value.add(delta));
    }

    @NonNull
    @Override
    public IPv6Address add(int delta) {
        return add(BigInteger.valueOf(delta));
    }

    @Override
    public int compareTo(@NonNull IPv6Address other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IPv6Address)) return false;
        return value.equals(((IPv6Address) o).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
