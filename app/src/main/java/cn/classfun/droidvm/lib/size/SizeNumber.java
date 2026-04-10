package cn.classfun.droidvm.lib.size;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class SizeNumber {
    public static final int DEFAULT_FLOAT_DOT = 2;
    private final BigDecimal number;
    private final SizeUnit unit;

    public SizeNumber(@NonNull BigDecimal number, @NonNull SizeUnit unit) {
        this.number = number;
        this.unit = unit;
    }

    public SizeNumber(@NonNull BigInteger number, @NonNull SizeUnit unit) {
        this(new BigDecimal(number), unit);
    }

    public SizeNumber(@NonNull SizeNumber other) {
        this(other.number, other.unit);
    }

    @SuppressWarnings("unused")
    public BigDecimal getNumber() {
        return number;
    }

    @SuppressWarnings("unused")
    public SizeUnit getUnit() {
        return unit;
    }

    @NonNull
    @SuppressWarnings("unused")
    public BigInteger getBytes() {
        return unit.toBytes(number);
    }

    @NonNull
    @SuppressWarnings("unused")
    public SizeNumber grow() {
        if (unit.ordinal() >= SizeUnit.MAX.ordinal())
            return new SizeNumber(this);
        return unit.getNext().calcPair(getBytes());
    }

    @NonNull
    @SuppressWarnings("unused")
    public SizeNumber shrink() {
        if (unit.ordinal() <= SizeUnit.MIN.ordinal())
            return new SizeNumber(this);
        return unit.getPrev().calcPair(getBytes());
    }

    @NonNull
    public String getNumberFloat(int dot) {
        return number
            .setScale(dot, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    }

    @NonNull
    public String getNumberFloat() {
        return getNumberFloat(DEFAULT_FLOAT_DOT);
    }

    @NonNull
    public String getNumberInteger() {
        return number
            .toBigIntegerExact()
            .toString();
    }

    @NonNull
    public String toString(int dot) {
        String valStr;
        if (dot == 0) {
            valStr = getNumberInteger();
        } else {
            valStr = getNumberFloat(dot);
        }
        return fmt("%s %s", valStr, unit.getString());
    }

    @NonNull
    @Override
    public String toString() {
        return toString(DEFAULT_FLOAT_DOT);
    }
}
