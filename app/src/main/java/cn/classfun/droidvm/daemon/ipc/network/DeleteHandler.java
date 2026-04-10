package cn.classfun.droidvm.daemon.ipc.network;

import static java.util.UUID.fromString;

import static cn.classfun.droidvm.lib.store.network.NetworkState.STOPPED;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import java.util.ArrayList;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMState;

@AutoService(RequestHandler.class)
public final class DeleteHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_delete";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var id = params.optString("network_id", "");
        if (id.isEmpty())
            throw new RequestException("missing network_id");
        var ctx = request.getContext();
        var vms = ctx.getVMs();
        var runningNames = new ArrayList<String>();
        vms.forEach((vmId, inst) -> {
            var nets = inst.item.opt("networks", DataItem.newArray());
            for (var iter : nets) {
                if (id.equals(iter.getValue().optString("network_id", ""))) {
                    if (inst.getState() != VMState.STOPPED)
                        runningNames.add(inst.getName());
                    break;
                }
            }
        });
        if (!runningNames.isEmpty())
            throw new RequestException(fmt(
                "network is in use by running VM: %s",
                String.join(", ", runningNames)));
        vms.forEach((vmId, inst) -> {
            var nets = inst.item.opt("networks", DataItem.newArray());
            for (int i = nets.size() - 1; i >= 0; i--) {
                if (id.equals(nets.get(i).optString("network_id", "")))
                    nets.remove(i);
            }
        });
        var networks = ctx.getNetworks();
        var inst = networks.findById(id);
        if (inst == null)
            throw new RequestException(fmt("network %s not found", id));
        var state = inst.getState();
        if (state != STOPPED && !inst.stop())
            throw new RequestException(fmt("Failed to stop network: %s", id));
        networks.removeById(fromString(id));
    }
}
