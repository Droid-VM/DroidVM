package cn.classfun.droidvm.daemon.ipc.network;

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
        return "network_exists";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var id = request.getParams().optString("network_id", "");
        if (id.isEmpty())
            throw new RequestException("missing network_id");
        var nets = request.getContext().getNetworks();
        var res = request.res();
        res.put("network_id", id);
        res.put("exists", nets.findById(id) != null);
    }
}
