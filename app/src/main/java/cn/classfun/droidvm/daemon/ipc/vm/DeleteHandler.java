package cn.classfun.droidvm.daemon.ipc.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.store.vm.VMState;

@AutoService(RequestHandler.class)
public final class DeleteHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_delete";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        var vms = request.getContext().getVMs();
        var inst = vms.findById(vmId);
        if (inst == null)
            throw new RequestException(fmt("VM not found: %s", vmId));
        if (inst.getState() != VMState.STOPPED && inst.stop())
            throw new RequestException(fmt("Failed to stop VM: %s", vmId));
        vms.removeById(inst.getId());
    }
}
