// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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
        // A VM the daemon never managed (created in the app but never started) has nothing to
        // stop and nothing to remove: deleting it is a no-op, not an error - the app deletes
        // its disks on our word that no process of ours holds them.
        if (inst == null) {
            request.res().put("existed", false);
            return;
        }
        if (inst.getState() != VMState.STOPPED && inst.stop())
            throw new RequestException(fmt("Failed to stop VM: %s", vmId));
        vms.removeById(inst.getId());
        request.res().put("existed", true);
    }
}
