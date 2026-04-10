package cn.classfun.droidvm.daemon.vm.backend;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.daemon.vm.VMBackendInstance;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

@SuppressWarnings("SameReturnValue")
public abstract class BackendBase {

    @NonNull
    public abstract String name();

    @NonNull
    public abstract VMBackendInstance create(@NonNull VMConfig config);
}
