package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

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
        if (!inst.item.optBoolean("vnc_enabled", false))
            throw new RequestException("VNC is not enabled for this VM");
        var res = request.res();
        var host = inst.item.optString("vnc_host", "");
        res.put("host", !host.isEmpty() ? host : "127.0.0.1");
        res.put("port", inst.item.optLong("vnc_port", -1));
        res.put("password", inst.item.optString("vnc_password", ""));
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
