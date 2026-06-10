package cn.classfun.droidvm.daemon.ipc.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.json.JSONObject;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class PortForwardListHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_port_forward_list";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var id = params.optString("vm_id", "");
        if (id.isEmpty())
            throw new RequestException("missing vm_id");
        var vm = request.getContext().getVMs().findById(id);
        if (vm == null)
            throw new RequestException(fmt("VM %s not found", id));
        var data = new JSONObject();
        data.put("configured", vm.getConfiguredPortForwards());
        data.put("active", vm.getActivePortForwards());
        request.res().put("data", data);
    }
}
