package cn.classfun.droidvm.daemon.network;

import static cn.classfun.droidvm.lib.store.network.NetworkState.RUNNING;
import static cn.classfun.droidvm.lib.store.network.NetworkState.STARTING;
import static cn.classfun.droidvm.lib.store.network.NetworkState.STOPPED;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

import cn.classfun.droidvm.daemon.network.backend.BackendBase;
import cn.classfun.droidvm.daemon.network.backend.FirewallHelper;
import cn.classfun.droidvm.daemon.network.backend.iptables.IptablesBackend;
import cn.classfun.droidvm.daemon.network.backend.LinuxNetwork;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.base.DataStore;

@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public final class NetworkInstanceStore extends DataStore<NetworkInstance> {
    private static final String TAG = "NetworkInstanceStore";
    public final FirewallHelper firewall = new IptablesBackend();
    public final BackendBase backend = new LinuxNetwork();
    public final ServerContext context;

    public NetworkInstanceStore(@NonNull ServerContext context) {
        super();
        this.context = context;
        Log.i(TAG, "Network instance store initialized");
    }

    @Nullable
    public String createNetwork(@NonNull NetworkConfig config) {
        var netId = config.getId();
        if (netId == null) {
            Log.e(TAG, "Cannot create network: missing id");
            return null;
        }
        var netIdStr = netId.toString();
        if (findById(netId) != null) {
            Log.w(TAG, fmt("Network %s already exists", netIdStr));
            return null;
        }
        var inst = getNetworkInstance(config, netIdStr);
        add(inst);
        Log.i(TAG, fmt("Created network: %s [%s] bridge=%s",
            config.getName(), netIdStr, config.item.optString("bridge_name", "")));
        return netIdStr;
    }

    @Nullable
    public String modifyNetwork(@NonNull NetworkConfig config) {
        var netId = config.getId();
        if (netId == null) {
            Log.e(TAG, "Cannot modify network: missing id");
            return null;
        }
        var netIdStr = netId.toString();
        var existing = findById(netId);
        if (existing == null) {
            Log.w(TAG, fmt("Network %s not found", netIdStr));
            return null;
        }
        if (existing.getState() != STOPPED) {
            Log.w(TAG, fmt("Network %s is not stopped, cannot modify", netIdStr));
            return null;
        }
        removeById(netId);
        var inst = getNetworkInstance(config, netIdStr);
        add(inst);
        Log.i(TAG, fmt("Modified network: %s [%s] bridge=%s",
            config.getName(), netIdStr, config.item.optString("bridge_name", "")));
        return netIdStr;
    }

    @NonNull
    private NetworkInstance getNetworkInstance(@NonNull NetworkConfig config, String netIdStr) {
        var inst = new NetworkInstance(this);
        inst.setId(netIdStr);
        inst.setName(config.getName());
        inst.item.set(config.item);
        return inst;
    }

    public void autoUp() {
        forEach((id, inst) -> {
            var s = inst.getState();
            if (!inst.item.optBoolean("auto_up", false) || s != STOPPED) return;
            Log.i(TAG, fmt("Auto-starting network %s [%s]", inst.getName(), id));
            if (!inst.start())
                Log.w(TAG, fmt("Failed to auto-start network %s [%s]", inst.getName(), id));
        });
    }

    @NonNull
    public JSONArray listNetworks() {
        var arr = new JSONArray();
        forEach((id, inst) -> {
            try {
                var obj = inst.toInfoJson();
                var s = inst.getState();
                if (s == RUNNING) {
                    var br = inst.item.optString("bridge_name", "");
                    obj.put("live_interfaces", backend.listBridgeInterfaces(br));
                    obj.put("live_addresses", backend.listAddresses(br));
                }
                arr.put(obj);
            } catch (JSONException e) {
                Log.w(TAG, "Failed to serialize network instance", e);
            }
        });
        return arr;
    }

    public void stopAll() {
        Log.i(TAG, "Stopping all networks...");
        var toStop = new ArrayList<NetworkInstance>();
        forEach((id, inst) -> {
            var s = inst.getState();
            if (s == RUNNING || s == STARTING)
                toStop.add(inst);
        });
        for (var inst : toStop)
            inst.stop();
        clear();
    }

    @NonNull
    @Override
    protected NetworkInstance create() {
        return new NetworkInstance(this);
    }

    @NonNull
    @Override
    protected NetworkInstance create(@NonNull JSONObject obj) throws JSONException {
        return new NetworkInstance(this, obj);
    }

    @NonNull
    @Override
    protected DataStore<NetworkInstance> createEmpty() {
        return new NetworkInstanceStore(context);
    }

    @NonNull
    @Override
    protected String getTypeName() {
        return "networks";
    }
}
