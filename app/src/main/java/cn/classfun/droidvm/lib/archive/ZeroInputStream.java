package cn.classfun.droidvm.lib.archive;

import androidx.annotation.NonNull;

import java.io.InputStream;
import java.util.Arrays;

/** A bounded stream of zero bytes, used to reproduce trailing alignment padding. */
public final class ZeroInputStream extends InputStream {
    private long remaining;

    public ZeroInputStream(long count) {
        this.remaining = Math.max(0, count);
    }

    @Override
    public int read() {
        if (remaining <= 0) return -1;
        remaining--;
        return 0;
    }

    @Override
    public int read(@NonNull byte[] b, int off, int len) {
        if (remaining <= 0) return -1;
        int n = (int) Math.min(len, remaining);
        Arrays.fill(b, off, off + n, (byte) 0);
        remaining -= n;
        return n;
    }
}
