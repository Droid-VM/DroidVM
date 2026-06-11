package cn.classfun.droidvm.daemon.network.backend;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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

    /** Backend health/status fields for network_info. */
    public abstract void appendInfo(@NonNull JSONObject obj) throws JSONException;

    /** Live per-VLAN IPv6 networks (DHCP-PD delegations); gateway-addressed. */
    @NonNull
    public Map<Integer, IPv6Network> getLiveV6Networks() {
        return Map.of();
    }
}
