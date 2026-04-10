package cn.classfun.droidvm.daemon.ipc.network;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class ListInterfacesHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_list_interfaces";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var res = request.res();
        var backend = request.getContext().getNetworks().backend;
        res.put("data", backend.listAvailableInterfaces());
    }
}
