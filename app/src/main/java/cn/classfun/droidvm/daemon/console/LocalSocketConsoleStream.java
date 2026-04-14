package cn.classfun.droidvm.daemon.console;

import android.net.LocalSocket;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import cn.classfun.droidvm.lib.store.vm.VMConfig;

public final class LocalSocketConsoleStream extends ConsoleStream {
    private LocalSocket socket;

    public LocalSocketConsoleStream(@NonNull VMConfig config, @NonNull String name, LocalSocket socket) {
        super(config, name);
        this.socket = socket;
    }

    public boolean isReady() {
        return socket != null && socket.isConnected();
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
        if (socket == null)
            return null;
        try {
            return socket.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    @Override
    public OutputStream getOutputStream() {
        if (socket == null)
            return null;
        try {
            return socket.getOutputStream();
        } catch (IOException e) {
            return null;
        }
    }

    public void setSocket(LocalSocket socket) {
        this.socket = socket;
    }

    @Override
    public void close() {
        super.close();
        if (socket != null) {
            try {
                socket.close();
            }catch (IOException ignored) {
            }
            socket = null;
        }
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var obj = super.toJson();
        obj.put("ready", isReady());
        return obj;
    }
}
