package cn.classfun.droidvm.daemon.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

import cn.classfun.droidvm.daemon.network.backend.BridgeBackend;
import cn.classfun.droidvm.daemon.network.backend.LinuxBridgeBackend;
import cn.classfun.droidvm.daemon.network.backend.gvisor.GvisorBridgeBackend;
import cn.classfun.droidvm.lib.network.IPNetwork;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkState;
import cn.classfun.droidvm.lib.store.vm.VMNicConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;

@SuppressWarnings({"unused", "BooleanMethodIsAlwaysInverted"})
public final class NetworkInstance extends NetworkConfig {
    private static final String TAG = "NetworkInstance";
    private NetworkState state = NetworkState.STOPPED;
    private final NetworkInstanceStore store;
    private BridgeBackend backend = null;

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
    public NetworkInstanceStore getStore() {
        return store;
    }

    @NonNull
    public NetworkState getState() {
        return state;
    }

    public void setState(@NonNull NetworkState state) {
        this.state = state;
    }

    @NonNull
    private BridgeBackend createBackend() {
        if (getBridgeType() == BridgeType.GVISOR)
            return new GvisorBridgeBackend(this);
        return new LinuxBridgeBackend(this);
    }

    @Nullable
    public BridgeBackend getBackend() {
        return backend;
    }

    public boolean start() {
        if (state != NetworkState.STOPPED) {
            Log.w(TAG, fmt("Network %s not stopped, cannot start", getName()));
            return false;
        }
        try {
            state = NetworkState.STARTING;
            backend = createBackend();
            backend.start();
            state = NetworkState.RUNNING;
            Log.i(TAG, fmt(
                "Network %s started on bridge %s", getName(), getBridgeName()
            ));
            return true;
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to start network %s", getName()), e);
            if (backend != null) {
                try {
                    backend.stop();
                } catch (Exception cleanup) {
                    Log.w(TAG, "Cleanup after failed start failed", cleanup);
                }
                backend = null;
            }
            state = NetworkState.STOPPED;
            return false;
        }
    }

    public boolean stop() {
        return stop(false);
    }

    public boolean stop(boolean force) {
        if (state != NetworkState.RUNNING) {
            Log.w(TAG, fmt("Network %s not running, cannot stop", getName()));
            return false;
        }
        if (!force) {
            var vm = findRunningVMUsing();
            if (vm != null) {
                Log.w(TAG, fmt(
                    "Network %s is in use by running VM %s, cannot stop",
                    getName(), vm
                ));
                return false;
            }
        }
        state = NetworkState.STOPPING;
        if (backend != null) {
            backend.stop();
            backend = null;
        }
        state = NetworkState.STOPPED;
        Log.i(TAG, fmt("Network %s stopped", getName()));
        return true;
    }

    /** Name of a non-stopped VM with a NIC on this network, or null. */
    @Nullable
    public String findRunningVMUsing() {
        var vms = store.context.getVMs();
        if (vms == null) return null;
        var netId = getId().toString();
        var found = new String[1];
        vms.forEach((vmId, vm) -> {
            if (found[0] != null) return;
            if (vm.getState() == VMState.STOPPED) return;
            vm.forEachNic(nic -> {
                if (netId.equals(nic.getNetworkId()))
                    found[0] = vm.getName();
            });
        });
        return found[0];
    }

    public void attachNic(@NonNull VMNicConfig nic, @NonNull String tapName) throws Exception {
        if (state != NetworkState.RUNNING || backend == null)
            throw new IllegalStateException(fmt("Network %s is not running", getName()));
        // The NIC may carry L3-only options (DHCP static lease / port forwards)
        // kept in its saved config for when it moves back to an L3 network.
        // Strip the ones this network can't honour into a throwaway copy so the
        // VM still boots; the stored config is untouched, and genuine
        // misconfigurations still fail validation below.
        var dropped = new java.util.ArrayList<String>();
        var effective = nic.sanitizedFor(this, dropped);
        if (!dropped.isEmpty())
            Log.i(TAG, fmt("NIC %s on network %s: ignoring unsupported options: %s",
                tapName, getName(), String.join(", ", dropped)));
        effective.validate(this);
        backend.attachNic(effective, tapName);
    }

    public void detachNic(@NonNull VMNicConfig nic, @NonNull String tapName) {
        if (backend == null) return;
        try {
            backend.detachNic(nic, tapName);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to detach NIC %s", tapName), e);
        }
    }

    @NonNull
    public Map<Integer, IPv6Network> getLiveV6Networks() {
        return backend != null ? backend.getLiveV6Networks() : Map.of();
    }

    /** Runtime-only address tools used by the info screen (Linux only). */
    public boolean addAddress(@NonNull IPNetwork<?, ?, ?> cidr) {
        if (getBridgeType() != BridgeType.LINUX || state != NetworkState.RUNNING)
            return false;
        return store.backend.addAddress(item.optString("bridge_name", ""), cidr);
    }

    public boolean removeAddress(@NonNull IPNetwork<?, ?, ?> cidr) {
        if (getBridgeType() != BridgeType.LINUX || state != NetworkState.RUNNING)
            return false;
        return store.backend.removeAddress(item.optString("bridge_name", ""), cidr);
    }

    public boolean addInterface(@NonNull String ifname) {
        if (getBridgeType() != BridgeType.LINUX || state != NetworkState.RUNNING)
            return false;
        return store.backend.addInterface(item.optString("bridge_name", ""), ifname);
    }

    public boolean removeInterface(@NonNull String ifname) {
        if (getBridgeType() != BridgeType.LINUX) return false;
        return store.backend.removeInterface(ifname);
    }

    public JSONArray listAddresses() {
        if (backend == null) return new JSONArray();
        return backend.listAddresses();
    }

    /** Three-dimension address rows (VLAN/bound/source), or null if unsupported. */
    @Nullable
    public JSONArray listAddressEntries() {
        return backend == null ? null : backend.listAddressEntries();
    }

    /** Per-VLAN DHCPv6-PD client state. */
    public JSONArray listPdStatus() {
        if (backend == null) return new JSONArray();
        return backend.listPdStatus();
    }

    /** Forces a PD renew on one VLAN's client. */
    public boolean renewPd(int vlanId) {
        return backend != null && backend.renewPd(vlanId);
    }

    /** Releases and re-solicits one VLAN's PD delegation. */
    public boolean releasePd(int vlanId) {
        return backend != null && backend.releasePd(vlanId);
    }

    /** Helper tools this network runs, as [{key, running}]. */
    public JSONArray listTools() {
        if (backend == null) return new JSONArray();
        return backend.listTools();
    }

    /** Captured stdout/stderr of a named tool, or null when unknown. */
    @Nullable
    public java.util.List<String> toolLog(@NonNull String key) {
        return backend == null ? null : backend.toolLog(key);
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
        if (backend == null) return new JSONArray();
        return backend.listInterfaces(store.context.getVMs());
    }

    public JSONArray listNeighbors() {
        if (backend == null) return new JSONArray();
        return backend.listNeighbors();
    }

    public JSONArray listDhcpLeases() {
        if (backend == null) return new JSONArray();
        return backend.listDhcpLeases();
    }

    /**
     * Devices the host routes for this network: the main bridge for the
     * untagged VLAN-0 domain plus the per-VLAN bridges of the tagged
     * VLANs (L3 mode only).
     */
    @NonNull
    public java.util.List<String> getL3Devices() {
        var out = new java.util.ArrayList<String>();
        var br = getBridgeName();
        if (br == null) return out;
        switch (getUplinkMode()) {
            case L3:
                for (var vlan : getVlans()) {
                    var dev = cn.classfun.droidvm.daemon.network.backend.LinuxNetwork
                        .vlanDevice(br, vlan.getVlanId());
                    if (!out.contains(dev)) out.add(dev);
                }
                break;
            default:
                break;
        }
        return out;
    }

    @NonNull
    public JSONObject toInfoJson() throws JSONException {
        var obj = item.toJson();
        obj.put("state", state.name().toLowerCase());
        if (backend != null) backend.appendInfo(obj);
        return obj;
    }

}
