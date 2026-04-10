package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class VncInfoHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_vnc_info";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        var inst = request.getContext().getVMs().findById(vmId);
        if (inst == null)
            throw new RequestException("VM not found");
        if (!inst.item.optBoolean("vnc_enabled", false))
            throw new RequestException("VNC is not enabled for this VM");
        var res = request.res();
        var host = inst.item.optString("vnc_host", "");
        res.put("host", !host.isEmpty() ? host : "127.0.0.1");
        res.put("port", inst.item.optLong("vnc_port", -1));
        res.put("password", inst.item.optString("vnc_password", ""));
    }
}
