package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.natives.NativeProcess.RLIM_INFINITY;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.daemon.console.ConsoleStream;
import cn.classfun.droidvm.lib.natives.NativeProcess;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.utils.FileUtils;

public abstract class VMBackendInstance {
    private static final String TAG = "VMBackendInstance";
    protected final Map<String, ConsoleStream> streams = new LinkedHashMap<>();
    protected final VMConfig config;
    private boolean stracePrepared = false;

    protected VMBackendInstance(@NonNull VMConfig config) {
        this.config = config;
    }

    @NonNull
    public abstract VMStartResult start();

    public abstract int runControlCommand(@NonNull String command);

    public abstract boolean hasControlSocket();

    public abstract void cleanup();

    /**
     * Writes pre-encoded evdev bytes to the running backend's native-display input channel on
     * behalf of the UI. Only the crosvm backend implements this; others report not-delivered.
     */
    public boolean writeNativeInput(int channel, @NonNull byte[] data) {
        return false;
    }

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
            pathJoin(DATA_DIR, "lib", fmt("libcompat_a%s.so", Build.VERSION.RELEASE)),
        };
        builder.environment("LD_PRELOAD", String.join(":", preload));
        builder.environment("LD_LIBRARY_PATH", pathJoin(DATA_DIR, "usr", "lib"));
        builder.maxOpenFiles(65536);
        builder.maxLockedMemory(RLIM_INFINITY);
        cleanUpMemory();
    }

    @NonNull
    protected String getStraceFolder() {
        var dirName = fmt("strace-%s", config.getId());
        var dir = pathJoin(DATA_DIR, "cache", dirName);
        var dirObj = new File(dir);
        if (!stracePrepared) {
            stracePrepared = true;
            if (dirObj.exists())
                FileUtils.shellRemoveTree(dir);
            if (!dirObj.mkdir())
                Log.w(TAG, "Failed to create strace directory");
        }
        return dir;
    }

    protected void prepareStraceArguments(@NonNull List<String> args) {
        var item = config.item;
        if (!item.optBoolean("strace", false)) return;
        var strace = getPrebuiltBinaryPath("strace");
        if (!new File(strace).exists())
            strace = FileUtils.findExecute("strace", null);
        if (strace == null || !new File(strace).exists()) {
            Log.e(TAG, "no strace binary found");
            return;
        }
        var dir = getStraceFolder();
        args.add(strace);
        args.add("-Tttrvyyff");
        args.add("-s");
        args.add("8192");
        args.add("-o");
        args.add(pathJoin(dir, "strace.log"));
    }
}
