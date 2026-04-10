package cn.classfun.droidvm.lib.size;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

public final class SizeUtils {
    private SizeUtils() {
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findUnit(
        BigInteger bytes, @NonNull SizeUnit[] units
    ) {
        for (int i = units.length - 1; i >= 0; i--)
            if (units[i].fitsExactly(bytes))
                return units[i].calcPair(bytes);
        return SizeUnit.B.calcPair(bytes);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findFloatUnit(
        BigDecimal bytes, @NonNull SizeUnit[] units
    ) {
        for (int i = units.length - 1; i >= 0; i--)
            if (units[i].isAtLeast(bytes))
                return units[i].calcPair(bytes);
        return SizeUnit.B.calcPair(bytes);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findFloatUnit(
        BigInteger bytes, @NonNull SizeUnit[] units
    ) {
        return findFloatUnit(new BigDecimal(bytes), units);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findUnit(
        long bytes, @NonNull SizeUnit[] units
    ) {
        return findUnit(BigInteger.valueOf(bytes), units);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findFloatUnit(
        long bytes, @NonNull SizeUnit[] units
    ) {
        return findFloatUnit(BigDecimal.valueOf(bytes), units);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findUnit(
        BigInteger bytes, @NonNull List<SizeUnit> units
    ) {
        return findUnit(bytes, units.toArray(new SizeUnit[0]));
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findFloatUnit(
        BigDecimal bytes, @NonNull List<SizeUnit> units
    ) {
        return findFloatUnit(bytes, units.toArray(new SizeUnit[0]));
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findFloatUnit(
        BigInteger bytes, @NonNull List<SizeUnit> units
    ) {
        return findFloatUnit(bytes, units.toArray(new SizeUnit[0]));
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findUnit(
        long bytes, @NonNull List<SizeUnit> units
    ) {
        return findUnit(bytes, units.toArray(new SizeUnit[0]));
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findFloatUnit(
        long bytes, @NonNull List<SizeUnit> units
    ) {
        return findFloatUnit(bytes, units.toArray(new SizeUnit[0]));
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findUnit(BigInteger bytes) {
        return findUnit(bytes, SizeUnit.values());
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findFloatUnit(BigDecimal bytes) {
        return findFloatUnit(bytes, SizeUnit.values());
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findFloatUnit(BigInteger bytes) {
        return findFloatUnit(bytes, SizeUnit.values());
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findUnit(long bytes) {
        return findUnit(bytes, SizeUnit.values());
    }

    @NonNull
    @SuppressWarnings("unused")
    public static SizeNumber findFloatUnit(long bytes) {
        return findFloatUnit(bytes, SizeUnit.values());
    }

    @NonNull
    public static String formatSize(long bytes, int dot) {
        return formatSize(BigInteger.valueOf(bytes), dot);
    }

    @NonNull
    public static String formatSize(BigInteger bytes, int dot) {
        SizeNumber pair;
        if (dot == 0) {
            pair = findUnit(bytes);
        } else {
            pair = findFloatUnit(new BigDecimal(bytes));
        }
        return pair.toString(dot);
    }

    @NonNull
    public static String formatSize(long bytes) {
        return formatSize(bytes, 2);
    }

    @NonNull
    public static String formatSize(BigInteger bytes) {
        return formatSize(bytes, 2);
    }

    @NonNull
    public static BigInteger parseBigSize(
        @NonNull String sizeStr
    ) throws NumberFormatException {
        var s = sizeStr.trim();
        if (s.isEmpty()) throw new NumberFormatException("Empty size string");
        int i = 0;
        if (s.charAt(i) == '+' || s.charAt(i) == '-') i++;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (i == 0) throw new NumberFormatException(fmt(
            "No number found in: %s", sizeStr
        ));
        var number = new BigDecimal(s.substring(0, i));
        var unitStr = s.substring(i).trim();
        if (unitStr.isEmpty() ||
            unitStr.equalsIgnoreCase("b") ||
            unitStr.equalsIgnoreCase("byte") ||
            unitStr.equalsIgnoreCase("bytes")
        ) return number.toBigIntegerExact();
        char prefix = unitStr.charAt(0);
        var unit = SizeUnit.fromString(unitStr);
        if (unit == null) throw new NumberFormatException(fmt(
            "Unknown unit prefix: %s", unitStr
        ));
        int ordinal = unit.ordinal();
        var suffix = unitStr.substring(1);
        boolean isSI = suffix.equals("b") &&
            Character.isUpperCase(prefix);
        if (!suffix.isEmpty() &&
            !suffix.equals("b") && !suffix.equals("B") &&
            !suffix.equalsIgnoreCase("iB")
        ) throw new NumberFormatException(fmt(
            "Unknown unit suffix: %s", unitStr
        ));
        var factor = isSI ?
            BigInteger.TEN.pow(3 * ordinal) :
            BigInteger.valueOf(2).pow(10 * ordinal);
        return number.multiply(new BigDecimal(factor)).toBigIntegerExact();
    }

    @SuppressWarnings("unused")
    public static long parseSize(@NonNull String sizeStr) throws NumberFormatException {
        var bs = parseBigSize(sizeStr);
        if (bs.bitLength() >= Long.SIZE)
            throw new NumberFormatException("Number too large");
        return bs.longValueExact();
    }
}
