package cn.classfun.droidvm.lib.utils;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;

public class BinaryUtils {
    private static final byte[] ZERO = new byte[64 * 1024];
    private BinaryUtils() {

    }

    public static long alignUp(long value) {
        return alignUp(value, 0x1000);
    }

    public static long alignUp(long value, long align) {
        long mask = align - 1L;
        return (value + mask) & ~mask;
    }

    public static long alignUpStrict(long value) {
        return alignUpStrict(value, 0x1000);
    }

    public static long alignUpStrict(long value, long align) {
        long aligned = alignUp(value);
        return aligned == value ? value + align : aligned;
    }

    public static void writeZero(@NonNull RandomAccessFile raf, long count) throws IOException {
        long rem = count;
        while (rem > 0) {
            int n = (int) Math.min(ZERO.length, rem);
            raf.write(ZERO, 0, n);
            rem -= n;
        }
    }

    @NonNull
    public static String decodeUtf8(@NonNull byte[] bytes) throws IOException {
        var decoder = UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            throw new IOException("vmpkg manifest is not valid UTF-8", e);
        }
    }

    public static void readZeroPadding(@NonNull InputStream in, long n) throws IOException {
        var buf = new byte[64 * 1024];
        long rem = n;
        while (rem > 0) {
            int toRead = (int) Math.min(buf.length, rem);
            int read = in.read(buf, 0, toRead);
            if (read < 0) throw new EOFException("unexpected end of vmpkg padding");
            for (int i = 0; i < read; i++)
                if (buf[i] != 0) throw new IOException("vmpkg padding is not zero-filled");
            rem -= read;
        }
    }

    public static int readSome(@NonNull InputStream in, @NonNull byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    public static void readFully(@NonNull InputStream in, @NonNull byte[] buf) throws IOException {
        int n = readSome(in, buf);
        if (n != buf.length) throw new EOFException("unexpected end of vmpkg");
    }

    public static int getUInt16LE(@NonNull byte[] buf, int off) {
        return (buf[off] & 0xff) | ((buf[off + 1] & 0xff) << 8);
    }

    public static long getInt64LE(@NonNull byte[] buf, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (buf[off + i] & 0xffL);
        return v;
    }

    public static void putUInt16LE(@NonNull byte[] buf, int off, int value) throws IOException {
        if (value < 0 || value > 0xffff)
            throw new IOException(fmt("uint16 out of range: %d", value));
        buf[off] = (byte) (value & 0xff);
        buf[off + 1] = (byte) ((value >>> 8) & 0xff);
    }

    public static void putInt64LE(@NonNull byte[] buf, int off, long value) throws IOException {
        if (value < 0) throw new IOException(fmt("uint64 out of range: %d", value));
        for (int i = 0; i < 8; i++) buf[off + i] = (byte) ((value >>> (i * 8)) & 0xff);
    }
}
