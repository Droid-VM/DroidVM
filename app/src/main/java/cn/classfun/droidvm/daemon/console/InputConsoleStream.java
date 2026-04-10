package cn.classfun.droidvm.daemon.console;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;

import cn.classfun.droidvm.lib.store.vm.VMConfig;

public final class InputConsoleStream extends ConsoleStream {
    private InputStream inputStream;

    public InputConsoleStream(
        @NonNull VMConfig config,
        @NonNull String name,
        @Nullable InputStream inputStream
    ) {
        super(config, name);
        this.inputStream = inputStream;
    }

    @Override
    public boolean isReadable() {
        return inputStream != null;
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    @Nullable
    @Override
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public void setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public void close() {
        super.close();
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
            inputStream = null;
        }
    }
}
