package cn.classfun.droidvm.daemon.console;

import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import cn.classfun.droidvm.lib.store.vm.VMConfig;

public final class FDPipeConsoleStream extends ConsoleStream {
    private ParcelFileDescriptor readPfd = null;
    private ParcelFileDescriptor writePfd = null;
    private InputStream inputStream = null;
    private OutputStream outputStream = null;

    public FDPipeConsoleStream(
        @NonNull VMConfig config,
        @NonNull String name,
        int readFd,
        int writeFd
    ) {
        super(config, name);
        setReadFd(readFd);
        setWriteFd(writeFd);
    }

    public void setReadFd(int readFd) {
        if (readFd >= 0) {
            readPfd = ParcelFileDescriptor.adoptFd(readFd);
            inputStream = new FileInputStream(readPfd.getFileDescriptor());
        }
    }

    public void setWriteFd(int writeFd) {
        if (writeFd >= 0) {
            writePfd = ParcelFileDescriptor.adoptFd(writeFd);
            outputStream = new FileOutputStream(writePfd.getFileDescriptor());
        }
    }

    public boolean isReady() {
        return readPfd != null || writePfd != null;
    }

    @Override
    public boolean isReadable() {
        return inputStream != null;
    }

    @Override
    public boolean isWritable() {
        return outputStream != null;
    }

    @Nullable
    @Override
    public InputStream getInputStream() {
        return inputStream;
    }

    @Nullable
    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public void setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public void setOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public void close() {
        super.close();
        inputStream = null;
        outputStream = null;
        if (readPfd != null) {
            try {
                readPfd.close();
            } catch (IOException ignored) {
            }
            readPfd = null;
        }
        if (writePfd != null) {
            try {
                writePfd.close();
            } catch (IOException ignored) {
            }
            writePfd = null;
        }
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var obj = super.toJson();
        obj.put("read_fd", readPfd != null ? readPfd.getFd() : -1);
        obj.put("write_fd", writePfd != null ? writePfd.getFd() : -1);
        obj.put("ready", isReady());
        return obj;
    }
}
