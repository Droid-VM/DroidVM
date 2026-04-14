package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.natives.NativeProcess.RLIM_INFINITY;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

import cn.classfun.droidvm.daemon.console.ConsoleStream;
import cn.classfun.droidvm.lib.natives.NativeProcess;

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

    private void cleanUpMemory() {
        run("echo madvise > /sys/kernel/mm/transparent_hugepage/enabled");
        run("echo madvise > /sys/kernel/mm/transparent_hugepage/defrag");
        run("echo advise > /sys/kernel/mm/transparent_hugepage/shmem_enabled");
        run("echo 3 > /proc/sys/vm/drop_caches");
        run("echo 1 > /proc/sys/vm/compact_memory");
    }

    protected void prepareProcess(@NonNull NativeProcess.Builder builder) {
        String[] preload = {
            pathJoin(DATA_DIR, "lib", "libsimpledump.so"),
        };
        builder.environment("LD_PRELOAD", String.join(":", preload));
        builder.environment("LD_LIBRARY_PATH", pathJoin(DATA_DIR, "usr", "lib"));
        builder.maxOpenFiles(65536);
        builder.maxLockedMemory(RLIM_INFINITY);
        cleanUpMemory();
    }
}
