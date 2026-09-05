// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.store.vm.DisplayExporter;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;

@AutoService(RequestHandler.class)
public final class VncInfoHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_vnc_info";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        var inst = request.getContext().getVMs().findById(vmId);
        if (inst == null)
            throw new RequestException("VM not found");
        // Which screen's server. A client that names none gets the first VNC-bound screen, which
        // is the only one a single-screen VM has and the one its default view opens.
        var screenId = params.optString("screen", "");
        VMScreenConfig screen = null;
        for (var candidate : VMScreenConfig.listOf(inst.item)) {
            if (!candidate.isEnabled() || candidate.getExporter() != DisplayExporter.VNC) continue;
            if (screenId.isEmpty() || screenId.equals(candidate.id)) {
                screen = candidate;
                break;
            }
        }
        if (screen == null)
            throw new RequestException("VNC is not enabled for this VM");
        var res = request.res();
        var host = screen.getVncHost();
        res.put("screen", screen.id);
        res.put("host", !host.isEmpty() ? host : "127.0.0.1");
        res.put("port", screen.getVncPort());
        res.put("password", screen.getVncPassword());
        // What this binding's transport ceiling permits. A permit and not a promise, which is why
        // there is no port beside it any more: whether an encoder is actually standing there is
        // answered on the RFB connection itself, by the capabilities rect, and a second port for
        // the console to be told about is exactly what that change removed.
        res.put("transport_cap", screen.getTransportCap().getToken());
        // When VNC binds to the IPv4 wildcard, resolve the phone's own LAN
        // address here from the router watcher's filtered host-IP set, which
        // already drops pbridge offload-proxy addresses parked on the uplink.
        // The client cannot exclude those itself: the offload tag is a netlink
        // route metric invisible to java.net.NetworkInterface, so its naive
        // interface enumeration would pick a guest proxy IP instead.
        if ("0.0.0.0".equals(host)) {
            var hostIps = request.getContext().getRouterWatcher().getHostIpv4Addresses();
            var it = hostIps.iterator();
            if (it.hasNext())
                res.put("remote_host", it.next());
        }
    }
}
