package cn.classfun.droidvm.lib.size;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum SizeUnit implements StringEnum {
    B, KB, MB, GB, TB, PB, EB, ZB, YB;

    public static final SizeUnit MIN = B;
    public static final SizeUnit MAX = YB;

    @NonNull
    public BigInteger getBigFactor() {
        return BigInteger.valueOf(2).pow(ordinal() * 10);
    }

    @SuppressWarnings("unused")
    public long getFactor() {
        int shift = ordinal() * 10;
        if (shift >= Long.SIZE)
            throw new ArithmeticException("Unit too large for long");
        return 1L << shift;
    }

    public boolean isAtLeast(@NonNull BigInteger bytes) {
        return bytes.compareTo(getBigFactor()) >= 0;
    }

    public boolean isAtLeast(@NonNull BigDecimal bytes) {
        return bytes.compareTo(new BigDecimal(getBigFactor())) >= 0;
    }

    public boolean isDivisible(@NonNull BigInteger bytes) {
        return bytes.mod(getBigFactor()).equals(BigInteger.ZERO);
    }

    public boolean fitsExactly(@NonNull BigInteger bytes) {
        return isAtLeast(bytes) && isDivisible(bytes);
    }

    @NonNull
    public BigDecimal calc(@NonNull BigDecimal bytes) {
        return bytes.divide(new BigDecimal(getBigFactor()), MathContext.UNLIMITED);
    }

    @NonNull
    public BigInteger calc(@NonNull BigInteger bytes) {
        return bytes.divide(getBigFactor());
    }

    @NonNull
    public SizeNumber calcPair(@NonNull BigDecimal bytes) {
        return new SizeNumber(calc(bytes), this);
    }

    @NonNull
    public SizeNumber calcPair(@NonNull BigInteger bytes) {
        return new SizeNumber(calc(bytes), this);
    }

    @NonNull
    public BigInteger toBytes(@NonNull BigDecimal number) {
        return number.multiply(new BigDecimal(getBigFactor())).toBigIntegerExact();
    }

    @NonNull
    @SuppressWarnings("unused")
    public BigInteger toBytes(@NonNull BigInteger number) {
        return number.multiply(getBigFactor());
    }

    @SuppressWarnings("unused")
    public long toBytes(long number) {
        var ret = toBytes(new BigDecimal(number));
        if (ret.bitLength() >= Long.SIZE)
            throw new ArithmeticException("Result too large for long");
        return ret.longValueExact();
    }

    public SizeUnit getNext() {
        if (ordinal() >= MAX.ordinal())
            throw new IllegalStateException("No larger unit");
        return values()[ordinal() + 1];
    }

    public SizeUnit getPrev() {
        if (ordinal() <= MIN.ordinal())
            throw new IllegalStateException("No smaller unit");
        return values()[ordinal() - 1];
    }

    @NonNull
    public String getString() {
        if (this == B) return "B";
        return fmt("%ciB", name().charAt(0));
    }

    @Nullable
    public static SizeUnit fromString(@NonNull String str) {
        if (str.isEmpty() ||
            str.equalsIgnoreCase("B") ||
            str.equalsIgnoreCase("Bytes") ||
            str.equalsIgnoreCase("Byte")
        ) return B;
        for (var unit : values())
            if (unit.getString().equalsIgnoreCase(str))
                return unit;
        if (str.length() == 1 || Character.toUpperCase(str.charAt(1)) == 'B') {
            char first = Character.toUpperCase(str.charAt(0));
            for (var unit : values()) {
                if (unit == B) continue;
                if (unit.getString().charAt(0) == first)
                    return unit;
            }
        }
        return null;
    }
}
