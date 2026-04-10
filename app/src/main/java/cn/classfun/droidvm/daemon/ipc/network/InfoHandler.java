package cn.classfun.droidvm.daemon.ipc.network;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import java.util.UUID;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.store.network.NetworkState;

@AutoService(RequestHandler.class)
public final class InfoHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_info";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var id = params.optString("network_id", "");
        if (id.isEmpty())
            throw new RequestException("missing network_id");
        var networks = request.getContext().getNetworks();
        var inst = networks.findById(UUID.fromString(id));
        if (inst == null)
            throw new RequestException("network not found");
        var res = request.res();
        var data = inst.toInfoJson();
        data.put("state", inst.getState().name());
        if (inst.getState() == NetworkState.RUNNING) {
            data.put("live_addresses", inst.listAddresses());
            data.put("live_interfaces", inst.listInterfaces());
            data.put("neighbors", inst.listNeighbors());
            if (inst.item.optBoolean("dhcp_enabled", false))
                data.put("dhcp_leases", inst.listDhcpLeases());
        }
        res.put("data", data);
    }

}
