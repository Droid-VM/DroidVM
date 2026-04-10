package cn.classfun.droidvm.daemon.ipc.network;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;

@AutoService(RequestHandler.class)
public final class CreateHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_create";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        if (!params.has("config"))
            throw new RequestException("missing config");
        var config = new NetworkConfig(params.getJSONObject("config"));
        var netId = request.getContext().getNetworks().createNetwork(config);
        if (netId == null || netId.isEmpty())
            throw new RequestException("failed to create network");
        var res = request.res();
        res.put("network_id", netId);
    }
}
