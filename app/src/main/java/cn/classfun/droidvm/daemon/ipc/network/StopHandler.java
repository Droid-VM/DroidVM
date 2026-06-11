package cn.classfun.droidvm.daemon.ipc.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class StopHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_stop";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var id = request.getParams().optString("network_id", "");
        if (id.isEmpty())
            throw new RequestException("missing network_id");
        var inst = request.getContext().getNetworks().findById(id);
        if (inst == null)
            throw new RequestException(fmt("network %s not found", id));
        var vm = inst.findRunningVMUsing();
        if (vm != null)
            throw new RequestException(fmt("network is in use by running VM %s", vm));
        if (!inst.stop())
            throw new RequestException("failed to stop network");
    }
}
