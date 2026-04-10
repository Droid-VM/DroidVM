package cn.classfun.droidvm.daemon.server;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;

import cn.classfun.droidvm.daemon.network.NetworkInstanceStore;
import cn.classfun.droidvm.daemon.network.backend.DefaultRouterWatcher;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;

public final class ServerContext {
    private static final String TAG = "ServerContext";
    private final VMInstanceStore vms = new VMInstanceStore(this);
    private final NetworkInstanceStore networks = new NetworkInstanceStore(this);
    private final DefaultRouterWatcher routerWatcher = new DefaultRouterWatcher(this);

    public ServerContext() {
        Log.i(TAG, "loading config files...");
        var filesDir = pathJoin(DATA_DIR, "files");
        vms.load(new File(filesDir, vms.getFileName()));
        networks.load(new File(filesDir, networks.getFileName()));
        Log.i(TAG, fmt("config files loaded: %d VMs, %d networks", vms.size(), networks.size()));
        vms.setNetworkStore(networks);
        run("pkill dnsmasq");
        try {
            networks.firewall.initialize();
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize firewall", e);
        }
        try {
            networks.autoUp();
        } catch (Exception e) {
            Log.w(TAG, "Failed to auto up networks", e);
        }
        try {
            vms.autoUp();
        } catch (Exception e) {
            Log.w(TAG, "Failed to auto up VMs", e);
        }
        try {
            routerWatcher.start();
        } catch (Exception e) {
            Log.w(TAG, "Failed to start router watcher", e);
        }
    }

    @NonNull
    public VMInstanceStore getVMs() {
        return vms;
    }

    @NonNull
    public NetworkInstanceStore getNetworks() {
        return networks;
    }

    @NonNull
    public DefaultRouterWatcher getRouterWatcher() {
        return routerWatcher;
    }
}
