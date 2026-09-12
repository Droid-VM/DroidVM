// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.json.JSONObject;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class UsbAttachHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "usb_attach";
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
        if (device.isEmpty())
            throw new RequestException("missing device");
        var manager = request.getContext().getUsb();
        var port = manager.attach(inst, device);
        var controller = manager.attachedController(device);
        var res = request.res();
        res.put("vm_id", vmId);
        res.put("device", device);
        res.put("port", port);
        // Which xHCI it landed on: the rule named one or it did not, and only the attach knows.
        res.put("controller", controller == null ? JSONObject.NULL : controller);
    }
}
