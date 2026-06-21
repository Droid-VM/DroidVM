package cn.classfun.droidvm.daemon.network.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.vm.VMNicConfig;

/**
 * Per-network runtime backing one bridge type: owns the data path (Linux
 * bridge or gvswitch), per-VLAN L3 services, NIC attach/detach including
 * static leases and port forwards, plus the live queries the info UI uses.
 */
public abstract class BridgeBackend {
    protected final NetworkInstance inst;

    protected BridgeBackend(@NonNull NetworkInstance inst) {
        this.inst = inst;
    }

    /** Brings the whole network up; throws with a user-readable reason. */
    public abstract void start() throws Exception;

    /** Tears everything down; must be safe to call after a failed start. */
    public abstract void stop();

    /**
     * Watchdog tick (every 5s while the network is RUNNING): restart any helper
     * process that has died and re-initialise its state. Must be idempotent and
     * a no-op when everything is healthy. Default: nothing to supervise.
     */
    public void reconcile() {}

    /**
     * Creates the tap device for one VM NIC and applies the per-port
     * settings (VLAN, isolation, MAC security), static DHCP leases and
     * port forwards.
     */
    public abstract void attachNic(@NonNull VMNicConfig nic, @NonNull String tapName)
        throws Exception;

    public abstract void detachNic(@NonNull VMNicConfig nic, @NonNull String tapName);

    public abstract JSONArray listAddresses();

    public abstract JSONArray listInterfaces(VMInstanceStore vms);

    public abstract JSONArray listNeighbors();

    public abstract JSONArray listDhcpLeases();

    /**
     * Address rows for network_info, one per address with three dimensions:
     * {@code {addr, family, vlan, bound, source}} where {@code source} is
     * {@code "configured"} (VMM-managed) or {@code "auto"} (link-local, RA,
     * DHCP-PD, ...). Returns {@code null} when the backend does not support
     * the richer model, in which case the UI falls back to its config + live
     * rendering. Default: unsupported.
     */
    @Nullable
    public JSONArray listAddressEntries() {
        return null;
    }

    /** Per-VLAN DHCPv6-PD client state for network_info; empty when none. */
    @NonNull
    public JSONArray listPdStatus() {
        return new JSONArray();
    }

    /** Builds one {@code {addr,family,vlan,bound,source}} row for {@link #listAddressEntries()}. */
    @NonNull
    protected static JSONObject addressEntry(
        @NonNull String cidr, int vlan, boolean bound, @NonNull String source
    ) {
        var o = new JSONObject();
        try {
            o.put("addr", cidr);
            o.put("family", cidr.contains(":") ? 6 : 4);
            o.put("vlan", vlan);
            o.put("bound", bound);
            o.put("source", source);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** Forces a PD renew on one VLAN's client; false when unsupported. */
    public boolean renewPd(int vlanId) {
        return false;
    }

    /** Releases and re-solicits one VLAN's PD delegation; false when unsupported. */
    public boolean releasePd(int vlanId) {
        return false;
    }

    /**
     * Helper tools this network runs (gvswitch / pbridge / bridgedhcp), as
     * {@code [{key, running}]}, so the info UI can list them and open each
     * one's captured log. Default: none.
     */
    @NonNull
    public JSONArray listTools() {
        return new JSONArray();
    }

    /** Captured stdout/stderr lines of a named tool, or null if unknown. */
    @Nullable
    public List<String> toolLog(@NonNull String key) {
        return null;
    }

    /** Builds one {@code {key, running}} tool descriptor for {@link #listTools()}. */
    @NonNull
    protected static JSONObject toolEntry(@NonNull String key, boolean running) {
        var o = new JSONObject();
        try {
            o.put("key", key);
            o.put("running", running);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** Backend health/status fields for network_info. */
    public abstract void appendInfo(@NonNull JSONObject obj) throws JSONException;

    /** Live per-VLAN IPv6 networks (DHCP-PD delegations); gateway-addressed. */
    @NonNull
    public Map<Integer, IPv6Network> getLiveV6Networks() {
        return Map.of();
    }

    /** Emits per-tap port-forward install failures into network_info. */
    protected static void appendForwardFailures(
        @NonNull JSONObject obj, @NonNull Map<String, List<String>> failures
    ) throws JSONException {
        if (failures.isEmpty()) return;
        var ff = new JSONObject();
        for (var e : failures.entrySet())
            ff.put(e.getKey(), new JSONArray(e.getValue()));
        obj.put("forward_failures", ff);
    }
}
