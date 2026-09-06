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
import cn.classfun.droidvm.daemon.usb.UsbRules;

/**
 * Puts one device where the caller says, now: the management page's direct action. It is not a
 * rules edit and does not run the rules -- what it does is attach, detach or deauthorize this
 * one device, and pin it so the next pass leaves it alone.
 */
@AutoService(RequestHandler.class)
public final class UsbSetTargetHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "usb_set_target";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        // The same addressing as usb_attach: the sysfs name, never the guest port, because two
        // of the three targets are about a device no VM holds.
        var device = params.optString("device", "");
        if (device.isEmpty())
            throw new RequestException("missing device");
        var wanted = params.optString("target", "");
        var target = UsbRules.Target.fromKey(wanted);
        if (target == null)
            throw new RequestException(fmt("unknown target: %s", wanted));
        var vms = request.getContext().getVMs();
        var vmId = params.optString("vm_id", "");
        var inst = vmId.isEmpty() ? null : vms.findById(vmId);
        if (target == UsbRules.Target.VM) {
            if (vmId.isEmpty())
                throw new RequestException("missing vm_id");
            if (inst == null)
                throw new RequestException(fmt("VM not found: %s", vmId));
        }
        var controller = params.optString("controller", "");
        var result = request.getContext().getUsb().setTarget(device, target,
            target == UsbRules.Target.VM ? inst : null,
            controller.isEmpty() ? null : controller);
        var res = request.res();
        var keys = result.keys();
        while (keys.hasNext()) {
            var key = keys.next();
            res.put(key, result.get(key));
        }
    }
}
