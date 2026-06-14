package cn.classfun.droidvm.daemon.ipc.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.store.vm.VMState;

@AutoService(RequestHandler.class)
public final class StartHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_start";
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
        if (params.optBoolean("clear_logs_before_start", false) &&
            inst.getState() == VMState.STOPPED)
            inst.clearLogs();
        // one-shot boot menu selection; empty = follow the stored config
        var bootEntry = params.optString("boot_entry", "");
        inst.setBootEntryOverride(bootEntry.isEmpty() ? null : bootEntry);
        if (!inst.start())
            throw new RequestException("failed to start VM");
    }
}
