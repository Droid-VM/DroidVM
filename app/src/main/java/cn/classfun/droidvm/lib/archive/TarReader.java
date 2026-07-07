package cn.classfun.droidvm.lib.archive;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.annotation.NonNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public final class TarReader implements AutoCloseable {
    private static final int BLOCK = 512;
    private final InputStream in;
    private boolean closed = false;
    private boolean eof = false;

    public TarReader(@NonNull InputStream in) {
        this.in = in;
    }

    public interface EntryHandler {
        void handle(
            @NonNull String name,
            int mode,
            long size,
            @NonNull InputStream content
        ) throws Exception;
    }

    public void forEach(@NonNull EntryHandler handler) throws Exception {
        while (!eof) {
            var hdr = readBlock();
            if (isNullBlock(hdr)) {
                eof = true;
                return;
            }
            String name = cstr(hdr, 0, 100);
            int type = hdr[156] & 0xff;
            long size = parseOctal(hdr, 124, 12);
            int mode = (int) parseOctal(hdr, 100, 8);
            if (type == 'L') {
                var nameBuf = new byte[(int) size];
                readFully(nameBuf);
                skipPad(size);
                name = new String(nameBuf, UTF_8).trim();
                hdr = readBlock();
                if (isNullBlock(hdr)) {
                    eof = true;
                    return;
                }
                size = parseOctal(hdr, 124, 12);
                mode = (int) parseOctal(hdr, 100, 8);
                type = hdr[156] & 0xff;
            }
            if (type != '0' && type != 0) {
                skipFully(size);
                skipPad(size);
                continue;
            }
            var limited = new LimitedInputStream(in, size);
            handler.handle(name, mode, size, limited);
            limited.drain();
            skipPad(size);
        }
    }

    private byte[] readBlock() throws IOException {
        var buf = new byte[BLOCK];
        int n = 0;
        while (n < BLOCK) {
            int r = in.read(buf, n, BLOCK - n);
            if (r < 0) {
                if (n == 0) {
                    eof = true;
                    return buf;
                }
                throw new EOFException("unexpected end of tar header");
            }
            n += r;
        }
        return buf;
    }

    private void readFully(@NonNull byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) throw new EOFException("unexpected end of tar entry");
            n += r;
        }
    }

    private void skipFully(long n) throws IOException {
        long rem = n;
        while (rem > 0) {
            long r = in.skip(rem);
            if (r <= 0) {
                if (in.read() < 0)
                    throw new EOFException("unexpected end of tar entry");
                rem--;
            } else {
                rem -= r;
            }
        }
    }

    private void skipPad(long size) throws IOException {
        int pad = (int) ((BLOCK - (size % BLOCK)) % BLOCK);
        if (pad > 0) skipFully(pad);
    }

    private static boolean isNullBlock(@NonNull byte[] hdr) {
        for (int i = 0; i < BLOCK; i++)
            if (hdr[i] != 0)
                return false;
        return true;
    }

    @NonNull
    private static String cstr(@NonNull byte[] hdr, int off, int len) {
        var end = off;
        while (end < off + len && hdr[end] != 0) end++;
        if (end == off) return "";
        return new String(hdr, off, end - off, UTF_8);
    }

    private static long parseOctal(@NonNull byte[] hdr, int off, int len) {
        var s = cstr(hdr, off, len);
        if (s.isEmpty()) return 0;
        var trimmed = s.trim();
        if (trimmed.isEmpty()) return 0;
        try {
            return Long.parseLong(trimmed, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        in.close();
    }
}
