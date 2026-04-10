package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class ListHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_list";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var res = request.res();
        res.put("data", request.getContext().getVMs().listVMs());
    }
}
