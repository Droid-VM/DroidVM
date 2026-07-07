package cn.classfun.droidvm.lib.archive;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public final class RandomAccessFileOutputStream extends OutputStream {
    @NonNull
    private final RandomAccessFile file;

    public RandomAccessFileOutputStream(@NonNull RandomAccessFile file) {
        this.file = file;
    }

    @Override
    public void write(int b) throws IOException {
        file.write(b);
    }

    @Override
    public void write(@NonNull byte[] b, int off, int len) throws IOException {
        file.write(b, off, len);
    }
}
