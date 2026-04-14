package cn.classfun.droidvm.daemon.vm.backend;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.vm.VMBackendInstance;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

@AutoService(BackendBase.class)
public final class CrosvmBackend extends BackendBase {
    @NonNull
    @Override
    public String name() {
        return "crosvm";
    }

    @NonNull
    @Override
    public VMBackendInstance create(@NonNull VMConfig config) {
        return new CrosvmBackendInstance(config);
    }
}
