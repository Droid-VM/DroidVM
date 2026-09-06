// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.basic;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.daemon.vm.UsbAcmPool;
import cn.classfun.droidvm.lib.store.base.DataItem;

@AutoService(RequestHandler.class)
public class SetAppConfig extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "set_app_config";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        if (!params.has("config"))
            throw new RuntimeException("missing config");
        var cfg = params.getJSONObject("config");
        var data = DataItem.fromJson(cfg);
        var ctx = request.getContext();
        ctx.appConfig = data;
        // The ACM pool is standing daemon state driven by this config: build or tear down
        // right away so toggling the setting acts without waiting for a VM start.
        UsbAcmPool.applyConfig(data);
    }
}
