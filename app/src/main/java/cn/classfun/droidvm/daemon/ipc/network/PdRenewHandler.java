package cn.classfun.droidvm.daemon.ipc.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class PdRenewHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_pd_renew";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var id = params.optString("network_id", "");
        if (id.isEmpty())
            throw new RequestException("missing network_id");
        var vlanId = params.optInt("vlan_id", -1);
        if (vlanId < 0)
            throw new RequestException("missing vlan_id");
        var inst = request.getContext().getNetworks().findById(id);
        if (inst == null)
            throw new RequestException(fmt("network %s not found", id));
        if (!inst.renewPd(vlanId))
            throw new RequestException("failed to renew PD delegation");
    }
}
