package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

@AutoService(RequestHandler.class)
public final class ModifyHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_modify";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        if (!params.has("config"))
            throw new RequestException("missing config");
        var config = new VMConfig(params.getJSONObject("config"));
        var vmId = request.getContext().getVMs().modifyVM(config);
        if (vmId == null || vmId.isEmpty())
            throw new RequestException("failed to modify VM");
        var res = request.res();
        res.put("vm_id", vmId);
    }
}

