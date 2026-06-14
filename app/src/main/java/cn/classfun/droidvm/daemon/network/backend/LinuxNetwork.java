package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;
import cn.classfun.droidvm.lib.network.IPNetwork;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;

/**
 * Linux data-path operations. Every netlink op goes through {@link Netbox}
 * (our bundled static rtnetlink CLI) instead of the system {@code ip}/{@code
 * bridge}, so behaviour is identical across OEMs. The only direct shell-outs
 * left are procfs writes (sysctl) and a sysfs existence check.
 */
@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "unused", "UnusedReturnValue"})
public final class LinuxNetwork extends BackendBase {
    private static final String TAG = "BridgeManager";

    @Override
    public boolean createBridge(@NonNull String name) {
        Log.i(TAG, fmt("Creating bridge: %s", name));
        return Netbox.linkAddBridge(name);
    }

    /**
     * Disables IPv6 RA acceptance on a host L3 device. The main bridge,
     * per-VLAN bridges and trunk legs are router LAN-side interfaces facing
     * the VMs; with the kernel default ({@code accept_ra=1}) a VM emitting
     * RAs could make the host autoconfigure an address or default route.
     * Best effort (the node is absent when IPv6 is disabled on the device).
     */
    public void disableAcceptRa(@NonNull String dev) {
        run("echo 0 > /proc/sys/net/ipv6/conf/%s/accept_ra", dev);
    }

    @Override
    public boolean deleteBridge(@NonNull String name) {
        Log.i(TAG, fmt("Deleting bridge: %s", name));
        return Netbox.linkDel(name);
    }

    /** base64url alphabet (A-Z a-z 0-9 - _); all legal in interface names. */
    private static final char[] B64URL =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    /**
     * Encodes a VLAN id as a fixed 2-char base64url code (the 12-bit VLAN
     * space packs into two 6-bit digits), keeping {@code {br}.{code}} /
     * {@code {br}v{code}} well within IFNAMSIZ (a 10-char bridge name + 3 = 13).
     */
    @NonNull
    public static String vlanCode(int vid) {
        int v = vid & 0xFFF;
        return new String(new char[]{B64URL[(v >> 6) & 0x3F], B64URL[v & 0x3F]});
    }

    /**
     * Per-VLAN Linux bridge that carries a tagged VLAN's L3 (gateway
     * address, DHCP, SNAT). Stock Android GKI has no bridge VLAN filtering,
     * so each tagged VLAN gets its own bridge instead of per-port VIDs on
     * one bridge. Never used for VLAN 0: see {@link #vlanDevice}.
     */
    @NonNull
    public static String perVlanBridge(@NonNull String bridge, int vid) {
        return fmt("%sv%s", bridge, vlanCode(vid));
    }

    /**
     * The device carrying a VLAN's L3. VLAN 0 is the untagged domain: its
     * traffic lives on the main bridge itself (the IP stack has no trunk
     * semantics — untagged frames are simply what the bridge device sees),
     * so no per-VLAN bridge and no 802.1q leg exist for it. An 802.1q
     * subinterface with VID 0 would receive only priority-tagged frames,
     * never the untagged traffic it is meant to serve.
     */
    @NonNull
    public static String vlanDevice(@NonNull String bridge, int vid) {
        return vid == 0 ? bridge : perVlanBridge(bridge, vid);
    }

    /**
     * 802.1q trunk leg: a subinterface on the main bridge (the tagged trunk
     * backbone) enslaved into the per-VLAN bridge, so trunk ports on the main
     * bridge exchange this VLAN's frames with the per-VLAN bridge's access
     * ports while staying isolated from other VLANs.
     */
    @NonNull
    public static String vlanTrunkLeg(@NonNull String bridge, int vid) {
        return fmt("%s.%s", bridge, vlanCode(vid));
    }

    /**
     * Brings up the per-VLAN bridge and its trunk leg: creates {br}v{hex},
     * the {br}.{hex} 802.1q subinterface on the main bridge, enslaves the leg
     * into the per-VLAN bridge and brings both up.
     */
    public boolean createVlanBridge(@NonNull String mainBr, int vid) {
        var perBr = perVlanBridge(mainBr, vid);
        var leg = vlanTrunkLeg(mainBr, vid);
        Log.i(TAG, fmt("Creating per-VLAN bridge %s + trunk leg %s (vid %d)",
            perBr, leg, vid));
        boolean ok = createBridge(perBr);
        ok &= Netbox.linkAddVlan(mainBr, leg, vid);
        ok &= addInterface(perBr, leg);
        ok &= setLinkState(leg, true);
        ok &= setLinkState(perBr, true);
        return ok;
    }

    /** Tears down the trunk leg and per-VLAN bridge created by createVlanBridge. */
    public void deleteVlanBridge(@NonNull String mainBr, int vid) {
        var perBr = perVlanBridge(mainBr, vid);
        var leg = vlanTrunkLeg(mainBr, vid);
        if (isInterfaceExists(leg)) Netbox.linkDel(leg);
        if (isInterfaceExists(perBr)) deleteBridge(perBr);
    }

    public boolean setPortIsolated(@NonNull String dev, boolean isolated) {
        return Netbox.linkSetIsolated(dev, isolated);
    }

    /**
     * Locks a bridge port so only static fdb entries may send (MAC
     * security). Requires kernel >= 5.16; callers degrade gracefully.
     */
    public boolean setPortLocked(@NonNull String dev, boolean locked) {
        return Netbox.linkSetLocked(dev, locked);
    }

    public boolean fdbAddStatic(@NonNull String mac, @NonNull String dev) {
        return Netbox.fdbAdd(mac, dev);
    }

    @Override
    public boolean createTap(@NonNull String name) {
        Log.i(TAG, fmt("Creating TAP: %s", name));
        return Netbox.linkAddTap(name);
    }

    @Override
    public boolean deleteTap(@NonNull String name) {
        Log.i(TAG, fmt("Deleting TAP: %s", name));
        return Netbox.linkDel(name);
    }

    @Override
    public boolean setLinkState(@NonNull String name, boolean up) {
        Log.i(TAG, fmt("Setting %s %s", name, up ? "up" : "down"));
        return Netbox.linkSetState(name, up);
    }

    @Override
    public boolean addInterface(@NonNull String bridge, @NonNull String iface) {
        Log.i(TAG, fmt("Adding %s to bridge %s", iface, bridge));
        return Netbox.linkSetMaster(iface, bridge);
    }

    @Override
    public boolean removeInterface(@NonNull String iface) {
        Log.i(TAG, fmt("Removing %s from bridge", iface));
        return Netbox.linkSetNomaster(iface);
    }

    @Override
    public boolean setMacAddress(@NonNull String dev, @NonNull String mac) {
        Log.i(TAG, fmt("Setting MAC %s on %s", mac, dev));
        return Netbox.linkSetMac(dev, mac);
    }

    @Override
    public boolean setStp(@NonNull String bridge, boolean enabled) {
        Log.i(TAG, fmt("Setting STP %s on %s", enabled ? "1" : "0", bridge));
        return Netbox.linkSetStp(bridge, enabled);
    }

    @Override
    public boolean isInterfaceExists(@NonNull String name) {
        return new File(fmt("/sys/class/net/%s/uevent", name)).exists();
    }

    @Override
    public boolean addAddress(@NonNull String dev, @NonNull IPNetwork<?, ?, ?> cidr) {
        Log.i(TAG, fmt("Adding address %s to %s", cidr, dev));
        return Netbox.addrAdd(dev, cidr.toString());
    }

    @Override
    public boolean removeAddress(@NonNull String dev, @NonNull IPNetwork<?, ?, ?> cidr) {
        Log.i(TAG, fmt("Removing address %s from %s", cidr, dev));
        return Netbox.addrDel(dev, cidr.toString());
    }

    @NonNull
    @Override
    public JSONArray listAddresses(@NonNull String dev) {
        return listAddresses(dev, null);
    }

    /**
     * Live addresses on {@code dev} as {@code "ip/plen"} strings; when
     * {@code metric} is non-null, only addresses tagged with that
     * IFA_RT_PRIORITY (pbridge's offload-proxy magic) are returned.
     */
    @NonNull
    public JSONArray listAddresses(@NonNull String dev, @Nullable Long metric) {
        var out = new JSONArray();
        var rows = Netbox.addrList(dev, metric);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            var local = r.optString("local", "");
            if (!local.isEmpty())
                out.put(fmt("%s/%d", local, r.optInt("prefixlen", 0)));
        }
        return out;
    }

    @NonNull
    @Override
    public JSONArray listBridges() {
        var bridges = new JSONArray();
        var rows = Netbox.linkList(null, true);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            var name = r.optString("ifname", "");
            try {
                var br = new JSONObject();
                br.put("name", name);
                br.put("state", r.optString("operstate", "UNKNOWN").toUpperCase());
                br.put("mtu", r.optInt("mtu", 1500));
                br.put("interfaces", listBridgeInterfaces(name));
                bridges.put(br);
            } catch (JSONException e) {
                Log.e(TAG, fmt("Failed to build bridge entry for %s", name), e);
            }
        }
        return bridges;
    }

    @NonNull
    @Override
    public JSONArray listBridgeInterfaces(@NonNull String bridge) {
        var members = new JSONArray();
        var rows = Netbox.linkList(bridge, false);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r != null) members.put(r.optString("ifname", ""));
        }
        return members;
    }

    @NonNull
    @Override
    public JSONArray listAvailableInterfaces() {
        var iface = new JSONArray();
        var rows = Netbox.linkList(null, false);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            var name = r.optString("ifname", "");
            if (name.equals("lo")) continue;
            if (r.optString("kind", "").equals("bridge")) continue;
            try {
                var obj = new JSONObject();
                obj.put("name", name);
                obj.put("state", r.optString("operstate", "UNKNOWN").toUpperCase());
                obj.put("master", r.optString("master", ""));
                iface.put(obj);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build interface entry", e);
            }
        }
        return iface;
    }

    @NonNull
    @Override
    public JSONArray listInterfaces(VMInstanceStore vms, String bridge) {
        var iFaces = new JSONArray();
        var rows = Netbox.linkList(bridge, false);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            var ifname = r.optString("ifname", "");
            try {
                var obj = new JSONObject();
                obj.put("name", ifname);
                obj.put("address", r.optString("address", ""));
                obj.put("state", r.optString("operstate", "UNKNOWN"));
                var vmInfo = vms.findVMByTap(ifname);
                if (vmInfo != null) {
                    obj.put("vm_id", vmInfo.optString("vm_id", ""));
                    obj.put("vm_name", vmInfo.optString("vm_name", ""));
                }
                iFaces.put(obj);
            } catch (Exception e) {
                Log.w(TAG, fmt("Error building interface entry for %s", ifname), e);
            }
        }
        return iFaces;
    }

    @NonNull
    @Override
    public JSONArray listNeighbors(String bridge) {
        // netbox's neigh-list already emits {dst,lladdr,dev,state[]}.
        return Netbox.neighList(bridge);
    }

    /** Routing-table ids consulted by any "lookup" policy rule, for {@link #populateRouteForNetwork}. */
    @NonNull
    public Set<String> listRouteTables(boolean ipv6) {
        var set = new LinkedHashSet<String>();
        var rows = Netbox.ruleList(ipv6);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null || !r.optBoolean("lookup", false)) continue;
            set.add(String.valueOf(r.optInt("table", 0)));
        }
        return set;
    }

    /** Adds one subnet to every policy routing table so host replies route back. */
    public void populateRouteForNetwork(@NonNull String dev, @NonNull IPNetwork<?, ?, ?> net) {
        boolean ipv6 = net instanceof IPv6Network;
        var spec = net.toNetworkString();
        for (var table : listRouteTables(ipv6))
            Netbox.routeAdd(dev, spec, table, ipv6);
    }

    @Override
    public void populateRuleRoute(@NonNull NetworkInstance inst) {
        var br = inst.getBridgeName();
        if (br == null) return;
        for (var vlan : inst.getVlans()) {
            var dev = vlanDevice(br, vlan.getVlanId());
            var net4 = vlan.getIpv4Network();
            if (net4 != null) populateRouteForNetwork(dev, net4);
            for (var cidr : vlan.getIpv4Secondary()) {
                try {
                    populateRouteForNetwork(dev, IPv4Network.parse(cidr));
                } catch (Exception ignored) {
                }
            }
            var net6 = vlan.getIpv6Network();
            if (net6 != null) populateRouteForNetwork(dev, net6);
            for (var cidr : vlan.getIpv6Secondary()) {
                try {
                    populateRouteForNetwork(dev, IPv6Network.parse(cidr));
                } catch (Exception ignored) {
                }
            }
        }
    }
}
