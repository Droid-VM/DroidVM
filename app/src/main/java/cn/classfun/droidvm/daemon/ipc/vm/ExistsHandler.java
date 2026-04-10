package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class ExistsHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_exists";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var id = request.getParams().optString("vm_id", "");
        if (id.isEmpty())
            throw new RequestException("missing vm_id");
        var vms = request.getContext().getVMs();
        var res = request.res();
        res.put("vm_id", id);
        res.put("exists", vms.findById(id) != null);
    }
}
