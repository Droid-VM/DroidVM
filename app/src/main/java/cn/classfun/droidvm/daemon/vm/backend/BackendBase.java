package cn.classfun.droidvm.daemon.vm.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import cn.classfun.droidvm.daemon.vm.VMBackendInstance;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

@SuppressWarnings("SameReturnValue")
public abstract class BackendBase {
    private static final String TAG = "BackendBase";
    private static final Map<String, BackendBase> backends = new HashMap<>();

    @NonNull
    public abstract String name();

    @NonNull
    public abstract VMBackendInstance create(@NonNull VMConfig config);

    public static void loadAll() {
        if (!backends.isEmpty()) return;
        for (var backend : ServiceLoader.load(BackendBase.class))
            backends.put(backend.name(), backend);
        Log.d(TAG, fmt("Loaded %d vm backends", backends.size()));
    }

    @Nullable
    public static BackendBase find(@NonNull String name) {
        return backends.get(name);
    }

}
