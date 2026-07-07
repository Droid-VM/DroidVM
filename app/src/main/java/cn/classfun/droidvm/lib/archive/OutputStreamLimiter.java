package cn.classfun.droidvm.lib.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;

public final class OutputStreamLimiter extends OutputStream {
    private final OutputStream out;
    private long remaining;
    private boolean closed = false;

    public OutputStreamLimiter(@NonNull OutputStream out, long size) {
        this.out = out;
        this.remaining = size;
    }

    @Override
    public void write(int b) throws IOException {
        if (closed || remaining <= 0)
            throw new IOException("entry overwrote its declared size");
        out.write(b);
        remaining--;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed)
            throw new IOException("entry closed");
        if (len > remaining)
            throw new IOException("entry overwrote its declared size");
        out.write(b, off, len);
        remaining -= len;
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void close() {
        closed = true;
    }

    public long getRemaining() {
        return remaining;
    }
}
