package cn.classfun.droidvm.daemon.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import cn.classfun.droidvm.daemon.network.backend.Dnsmasq;
import cn.classfun.droidvm.lib.network.IPNetwork;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkState;

@SuppressWarnings({"unused", "BooleanMethodIsAlwaysInverted"})
public final class NetworkInstance extends NetworkConfig {
    private static final String TAG = "NetworkInstance";
    private NetworkState state = NetworkState.STOPPED;
    private final NetworkInstanceStore store;
    private Dnsmasq dnsmasq = null;

    public NetworkInstance(
        @NonNull NetworkInstanceStore store
    ) {
        super();
        this.store = store;
    }

    public NetworkInstance(
        @NonNull NetworkInstanceStore store,
        @NonNull JSONObject obj
    ) throws JSONException {
        super(obj);
        this.store = store;
        if (obj.has("state"))
            state = NetworkState.valueOf(obj.getString("state"));
    }

    @NonNull
    public NetworkState getState() {
        return state;
    }

    public void setState(@NonNull NetworkState state) {
        this.state = state;
    }

    public boolean isDnsmasqRunning() {
        if (dnsmasq == null) return false;
        return dnsmasq.isDnsmasqRunning();
    }

    public int getDnsmasqExitCode() {
        if (dnsmasq == null) return -1;
        return dnsmasq.getDnsmasqExitCode();
    }

    public boolean start() {
        if (state != NetworkState.STOPPED) {
            Log.w(TAG, fmt("Network %s not stopped, cannot start", getName()));
            return false;
        }
        var br = item.optString("bridge_name", "");
        try {
            state = NetworkState.STARTING;
            if (store.backend.isInterfaceExists(br)) {
                Log.w(TAG, fmt("Interface %s already exists, deleting", br));
                store.backend.deleteBridge(br);
            }
            if (!store.backend.createBridge(br))
                throw new RuntimeException(fmt("Failed to create bridge %s", br));
            if (!store.backend.isInterfaceExists(br))
                throw new RuntimeException(fmt("Interface %s is not a bridge", br));
            var mac = item.optString("mac_address", "");
            if (!mac.isEmpty() && !store.backend.setMacAddress(br, mac))
                Log.w(TAG, fmt("Failed to set MAC %s on %s", mac, br));
            store.backend.setStp(br, item.optBoolean("stp", false));
            item.get("ipv4_addresses").forEachArray(a -> {
                try {
                    var addr = IPv4Network.parse(a.asString());
                    if (!store.backend.addAddress(br, addr))
                        Log.w(TAG, fmt("Failed to add IPv4 %s to %s", addr, br));
                } catch (Exception ignored) {
                }
            });
            item.get("ipv6_addresses").forEachArray(a -> {
                try {
                    var addr = IPv6Network.parse(a.asString());
                    if (!store.backend.addAddress(br, addr))
                        Log.w(TAG, fmt("Failed to add IPv6 %s to %s", addr, br));
                } catch (Exception ignored) {
                }
            });
            if (!store.backend.setLinkState(br, true))
                throw new RuntimeException(fmt("Failed to bring up bridge %s", br));
            store.firewall.initNetwork(this);
            store.backend.populateRuleRoute(this);
            store.context.getRouterWatcher().setForNewNetwork(this);
            var dhcpEnabled = item.optBoolean("dhcp_enabled", false);
            var dhcpStart = item.optString("dhcp_range_start", "");
            var dhcpEnd = item.optString("dhcp_range_end", "");
            if (dhcpEnabled && !dhcpStart.isEmpty() && !dhcpEnd.isEmpty()) {
                if (dnsmasq == null) dnsmasq = new Dnsmasq(this);
                dnsmasq.startDnsmasq();
            }
            state = NetworkState.RUNNING;
            Log.i(TAG, fmt("Network %s started on bridge %s", getName(), br));
            return true;
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to start network %s", getName()), e);
            state = NetworkState.STOPPED;
            store.backend.deleteBridge(br);
            return false;
        }
    }

    public boolean stop() {
        if (state != NetworkState.RUNNING) {
            Log.w(TAG, fmt("Network %s not running, cannot stop", getName()));
            return false;
        }
        state = NetworkState.STOPPING;
        var br = item.optString("bridge_name", "");
        if (dnsmasq != null)
            dnsmasq.stopDnsmasq();
        store.firewall.deinitNetwork(this);
        store.backend.setLinkState(br, false);
        if (!store.backend.deleteBridge(br))
            Log.w(TAG, fmt("Failed to delete bridge %s", br));
        state = NetworkState.STOPPED;
        Log.i(TAG, fmt("Network %s stopped", getName()));
        return true;
    }

    public boolean addAddress(@NonNull IPNetwork<?, ?, ?> cidr) {
        if (!store.backend.addAddress(item.optString("bridge_name", ""), cidr)) return false;
        var key = (cidr instanceof IPv6Network) ? "ipv6_addresses" : "ipv4_addresses";
        var arr = item.get(key);
        if (arr.is(DataItem.Type.NULL)) {
            arr = DataItem.newArray();
            item.set(key, arr);
        }
        arr.append(DataItem.newString(cidr.toString()));
        return true;
    }

    public boolean removeAddress(@NonNull IPNetwork<?, ?, ?> cidr) {
        return store.backend.removeAddress(item.optString("bridge_name", ""), cidr);
    }

    public boolean addInterface(@NonNull String ifname) {
        return store.backend.addInterface(item.optString("bridge_name", ""), ifname);
    }

    public boolean removeInterface(@NonNull String ifname) {
        return store.backend.removeInterface(ifname);
    }

    public JSONArray listAddresses() {
        return store.backend.listAddresses(item.optString("bridge_name", ""));
    }

    public JSONArray listBridges() {
        return store.backend.listBridges();
    }

    public JSONArray listBridgeInterfaces() {
        return store.backend.listBridgeInterfaces(item.optString("bridge_name", ""));
    }

    public JSONArray listAvailableInterfaces() {
        return store.backend.listAvailableInterfaces();
    }

    public JSONArray listInterfaces() {
        var vms = store.context.getVMs();
        return store.backend.listInterfaces(vms, item.optString("bridge_name", ""));
    }

    public JSONArray listNeighbors() {
        return store.backend.listNeighbors(item.optString("bridge_name", ""));
    }

    public JSONArray listDhcpLeases() {
        return store.backend.listDhcpLeases(item.optString("bridge_name", ""));
    }

    @NonNull
    public JSONObject toInfoJson() throws JSONException {
        var obj = item.toJson();
        obj.put("state", state.name().toLowerCase());
        obj.put("dnsmasq_running", isDnsmasqRunning());
        obj.put("dnsmasq_exit_code", getDnsmasqExitCode());
        return obj;
    }
}
