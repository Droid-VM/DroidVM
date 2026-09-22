// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.usb;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;

/** A dry run of the rules over every plugged device: reports what each would do, attaches none. */
@AutoService(RequestHandler.class)
public final class UsbRulesTestHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "usb_rules_test";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var res = request.res();
        var usb = request.getContext().getUsb();
        // The switch beside the dry run, because off it is the answer to every row: a rule set
        // that matches nothing and one that is not allowed to run read the same in the table.
        res.put("enabled", usb.getRules().isEnabled());
        res.put("devices", usb.testRules());
    }
}
