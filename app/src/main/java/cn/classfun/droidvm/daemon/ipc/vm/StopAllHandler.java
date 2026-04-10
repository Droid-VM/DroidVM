package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class StopAllHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_stop_all";
    }

    @Override
    public void handle(@NonNull ClientRequest request) {
        request.getContext().getVMs().stopAll();
    }
}
