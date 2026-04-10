package cn.classfun.droidvm.daemon.ipc.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class AddInterfaceHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_add_interface";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var id = params.optString("network_id", "");
        var iface = params.optString("interface", "");
        if (id.isEmpty())
            throw new RequestException("missing network_id");
        if (iface.isEmpty())
            throw new RequestException("missing interface");
        var inst = request.getContext().getNetworks().findById(id);
        if (inst == null)
            throw new RequestException(fmt("network %s not found", id));
        if (!inst.addInterface(iface))
            throw new RequestException("failed to add interface");
    }
}
