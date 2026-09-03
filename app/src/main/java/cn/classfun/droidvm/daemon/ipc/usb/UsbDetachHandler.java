// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class UsbDetachHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "usb_detach";
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
        var device = params.optString("device", "");
        // Either address works: the sysfs name the caller attached, or the port it was given back.
        // A JSON null is a caller that sent the key without a value, which is not an address.
        Integer port = params.has("port") && !params.isNull("port")
            ? params.optInt("port", -1) : null;
        if (device.isEmpty() && port == null)
            throw new RequestException("missing device or port");
        request.getContext().getUsb().detach(inst, device.isEmpty() ? null : device, port);
    }
}
