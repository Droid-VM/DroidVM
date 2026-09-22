// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.usb;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class UsbRulesSetHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "usb_rules_set";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var rules = request.getParams().optJSONObject("rules");
        if (rules == null)
            throw new RequestException("missing rules");
        var applied = request.getContext().getUsb().setRules(rules);
        var res = request.res();
        res.put("applied", applied);
    }
}
