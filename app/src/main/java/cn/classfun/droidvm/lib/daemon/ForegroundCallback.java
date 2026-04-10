package cn.classfun.droidvm.lib.daemon;

import org.json.JSONObject;

import java.util.UUID;

import cn.classfun.droidvm.lib.store.vm.VMState;

public interface ForegroundCallback {
    @SuppressWarnings({"unused", "EmptyMethod"})
    default void onVMExited(UUID vmId, String vmName, int exitCode, JSONObject data) {
    }

    @SuppressWarnings({"unused", "EmptyMethod"})
    default void onVMStateChanged(UUID vmId, VMState state) {
    }
}
