package cn.classfun.droidvm.lib.archive;

import androidx.annotation.NonNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public final class LimitedInputStream extends InputStream {
    private final InputStream in;
    private long remaining;
    private boolean closed = false;

    public LimitedInputStream(@NonNull InputStream in, long size) {
        this.in = in;
        this.remaining = size;
    }

    @Override
    public int read() throws IOException {
        if (closed) return -1;
        if (remaining <= 0) return -1;
        int b = in.read();
        if (b >= 0) remaining--;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed || remaining <= 0) return -1;
        int toRead = (int) Math.min(len, remaining);
        int n = in.read(b, off, toRead);
        if (n > 0) remaining -= n;
        return n;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public void close() {
        closed = true;
    }

    public long getRemaining() {
        return remaining;
    }

    public void drain() throws IOException {
        while (remaining > 0) {
            long r = in.skip(remaining);
            if (r <= 0) {
                if (in.read() < 0)
                    throw new EOFException("unexpected end of tar entry");
                remaining--;
            } else {
                remaining -= r;
            }
        }
    }
}