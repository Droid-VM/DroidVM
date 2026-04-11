package cn.classfun.droidvm.daemon.console;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;

import cn.classfun.droidvm.lib.network.FDSocket;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

@SuppressWarnings("unused")
public final class FDSocketConsoleStream extends ConsoleStream {
    private FDSocket socket;

    public FDSocketConsoleStream(@NonNull VMConfig config, @NonNull String name, int fd) {
        super(config, name);
        this.socket = new FDSocket(fd);
    }

    public int getFd() {
        return socket != null ? socket.getFd() : -1;
    }

    public boolean isReady() {
        return socket != null && socket.isOpen();
    }

    @Override
    public boolean isReadable() {
        return socket != null;
    }

    @Override
    public boolean isWritable() {
        return socket != null;
    }

    @Nullable
    @Override
    public InputStream getInputStream() {
        return socket != null ? socket.getInputStream() : null;
    }

    @Nullable
    @Override
    public OutputStream getOutputStream() {
        return socket != null ? socket.getOutputStream() : null;
    }

    public void setSocket(FDSocket socket) {
        this.socket = socket;
    }

    @Override
    public void close() {
        super.close();
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var obj = super.toJson();
        obj.put("fd", getFd());
        obj.put("ready", isReady());
        return obj;
    }
}
