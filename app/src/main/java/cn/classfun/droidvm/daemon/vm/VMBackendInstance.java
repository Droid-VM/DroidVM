package cn.classfun.droidvm.daemon.vm;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

import cn.classfun.droidvm.daemon.console.ConsoleStream;

public abstract class VMBackendInstance {
    protected final Map<String, ConsoleStream> streams = new LinkedHashMap<>();

    @NonNull
    public abstract VMStartResult start();

    public abstract int runControlCommand(@NonNull String command);

    public abstract boolean hasControlSocket();

    public abstract void cleanup();

    @NonNull
    @SuppressWarnings("unused")
    public Map<String, ConsoleStream> getStreams() {
        return streams;
    }

    public void addStream(@NonNull ConsoleStream stream) {
        streams.put(stream.getName(), stream);
    }
}
