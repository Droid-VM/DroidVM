package cn.classfun.droidvm.lib.network;

import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class FDSocket implements Closeable {
    private ParcelFileDescriptor pfd;
    private FileInputStream inputStream;
    private FileOutputStream outputStream;

    @SuppressWarnings("unused")
    public FDSocket(int fd) {
        this.pfd = ParcelFileDescriptor.adoptFd(fd);
        this.inputStream = new FileInputStream(pfd.getFileDescriptor());
        this.outputStream = new FileOutputStream(pfd.getFileDescriptor());
    }

    public int getFd() {
        return pfd != null ? pfd.getFd() : -1;
    }

    @NonNull
    public InputStream getInputStream() {
        return inputStream;
    }

    @NonNull
    public OutputStream getOutputStream() {
        return outputStream;
    }

    public boolean isOpen() {
        return pfd != null;
    }

    @Override
    public void close() {
        inputStream = null;
        outputStream = null;
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
            pfd = null;
        }
    }
}
