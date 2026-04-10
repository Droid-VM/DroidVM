package cn.classfun.droidvm.daemon.ipc.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.network.IPNetwork;

@AutoService(RequestHandler.class)
public final class AddAddressHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_add_address";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var id = params.optString("network_id", "");
        var cidr = params.optString("address", "");
        if (id.isEmpty())
            throw new RequestException("missing network_id");
        if (cidr.isEmpty())
            throw new RequestException("missing address");
        IPNetwork<?, ?, ?> network;
        try {
            network = IPNetwork.parse(cidr);
        } catch (IllegalArgumentException e) {
            throw new RequestException(fmt("invalid address: %s", e.getMessage()));
        }
        var inst = request.getContext().getNetworks().findById(id);
        if (inst == null)
            throw new RequestException(fmt("network %s not found", id));
        if (!inst.addAddress(network))
            throw new RequestException("failed to add address");
    }
}
