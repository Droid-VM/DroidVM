package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.readFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;
import cn.classfun.droidvm.lib.network.IPNetwork;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;

@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "unused", "UnusedReturnValue"})
public final class LinuxNetwork extends BackendBase {
    private static final String TAG = "BridgeManager";

    @Override
    public boolean createBridge(@NonNull String name) {
        Log.i(TAG, fmt("Creating bridge: %s", name));
        var result = runList("ip", "link", "add", name, "type", "bridge");
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean deleteBridge(@NonNull String name) {
        Log.i(TAG, fmt("Deleting bridge: %s", name));
        var result = runList("ip", "link", "delete", name, "type", "bridge");
        result.printLog(TAG);
        return result.isSuccess();
    }

    /**
     * Resolves the iproute2 "bridge" tool: prebuilt, then system, then PATH.
     */
    @NonNull
    public static String resolveBridgeTool() {
        var prebuilt = getPrebuiltBinaryPath("bridge");
        if (new File(prebuilt).canExecute()) return prebuilt;
        if (new File("/system/bin/bridge").canExecute()) return "/system/bin/bridge";
        return "bridge";
    }

    public boolean setVlanFiltering(@NonNull String bridge, boolean enabled) {
        var val = enabled ? "1" : "0";
        Log.i(TAG, fmt("Setting vlan_filtering %s on %s", val, bridge));
        var result = runList(
            "ip", "link", "set", "dev", bridge,
            "type", "bridge", "vlan_filtering", val
        );
        result.printLog(TAG);
        return result.isSuccess();
    }

    public boolean bridgeVlanAdd(
        @NonNull String dev, int vid, boolean pvid, boolean untagged, boolean self
    ) {
        var cmd = new ArrayList<>(List.of(
            resolveBridgeTool(), "vlan", "add", "dev", dev, "vid", String.valueOf(vid)
        ));
        if (pvid) cmd.add("pvid");
        if (untagged) cmd.add("untagged");
        if (self) cmd.add("self");
        var result = runList(cmd);
        result.printLog(TAG);
        return result.isSuccess();
    }

    public boolean bridgeVlanDel(@NonNull String dev, int vid, boolean self) {
        var cmd = new ArrayList<>(List.of(
            resolveBridgeTool(), "vlan", "del", "dev", dev, "vid", String.valueOf(vid)
        ));
        if (self) cmd.add("self");
        var result = runList(cmd);
        result.printLog(TAG);
        return result.isSuccess();
    }

    /** Creates the {bridge}.{vid} 802.1q subinterface carrying that VLAN's L3 config. */
    public boolean addVlanSubInterface(@NonNull String bridge, int vid) {
        var name = vlanSubInterface(bridge, vid);
        Log.i(TAG, fmt("Creating VLAN subinterface %s", name));
        var result = runList(
            "ip", "link", "add", "link", bridge, "name", name,
            "type", "vlan", "id", String.valueOf(vid)
        );
        result.printLog(TAG);
        return result.isSuccess();
    }

    @NonNull
    public static String vlanSubInterface(@NonNull String bridge, int vid) {
        return fmt("%s.%d", bridge, vid);
    }

    public boolean setPortIsolated(@NonNull String dev, boolean isolated) {
        var result = runList(
            "ip", "link", "set", "dev", dev,
            "type", "bridge_slave", "isolated", isolated ? "on" : "off"
        );
        result.printLog(TAG);
        return result.isSuccess();
    }

    /**
     * Locks a bridge port so only static fdb entries may send (MAC
     * security). Requires kernel >= 5.16; callers degrade gracefully.
     */
    public boolean setPortLocked(@NonNull String dev, boolean locked) {
        var result = runList(
            "ip", "link", "set", "dev", dev,
            "type", "bridge_slave", "locked", locked ? "on" : "off"
        );
        result.printLog(TAG);
        return result.isSuccess();
    }

    public boolean fdbAddStatic(@NonNull String mac, @NonNull String dev) {
        var result = runList(
            resolveBridgeTool(), "fdb", "add", mac, "dev", dev, "master", "static"
        );
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean createTap(@NonNull String name) {
        Log.i(TAG, fmt("Creating TAP: %s", name));
        var result = runList(
            "ip", "tuntap", "add", "dev", name, "mode", "tap", "vnet_hdr"
        );
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean deleteTap(@NonNull String name) {
        Log.i(TAG, fmt("Deleting TAP: %s", name));
        var result = runList("ip", "link", "delete", name);
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean setLinkState(@NonNull String name, boolean up) {
        var state = up ? "up" : "down";
        Log.i(TAG, fmt("Setting %s %s", name, state));
        var result = runList("ip", "link", "set", name, state);
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean addInterface(@NonNull String bridge, @NonNull String iface) {
        Log.i(TAG, fmt("Adding %s to bridge %s", iface, bridge));
        var result = runList("ip", "link", "set", iface, "master", bridge);
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean removeInterface(@NonNull String iface) {
        Log.i(TAG, fmt("Removing %s from bridge", iface));
        var result = runList("ip", "link", "set", iface, "nomaster");
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean setMacAddress(@NonNull String dev, @NonNull String mac) {
        Log.i(TAG, fmt("Setting MAC %s on %s", mac, dev));
        var result = runList("ip", "link", "set", "dev", dev, "address", mac);
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean setStp(@NonNull String bridge, boolean enabled) {
        var val = enabled ? "1" : "0";
        Log.i(TAG, fmt("Setting STP %s on %s", val, bridge));
        var result = runList(
            "ip", "link", "set", "dev", bridge,
            "type", "bridge", "stp_state", val
        );
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean isInterfaceExists(@NonNull String name) {
        return new File(fmt("/sys/class/net/%s/uevent", name)).exists();
    }

    @Override
    public boolean addAddress(@NonNull String dev, @NonNull IPNetwork<?, ?, ?> cidr) {
        Log.i(TAG, fmt("Adding address %s to %s", cidr, dev));
        var result = runList("ip", "addr", "add", cidr.toString(), "dev", dev);
        result.printLog(TAG);
        return result.isSuccess();
    }

    @Override
    public boolean removeAddress(@NonNull String dev, @NonNull IPNetwork<?, ?, ?> cidr) {
        Log.i(TAG, fmt("Removing address %s from %s", cidr, dev));
        var result = runList("ip", "addr", "del", cidr.toString(), "dev", dev);
        result.printLog(TAG);
        return result.isSuccess();
    }

    @NonNull
    @Override
    public JSONArray listAddresses(@NonNull String dev) {
        var addr = new JSONArray();
        var result = runListQuiet("ip", "-j", "addr", "show", "dev", dev);
        if (!result.isSuccess()) return addr;
        try {
            var arr = new JSONArray(result.getOutString());
            for (int i = 0; i < arr.length(); i++) {
                var link = arr.getJSONObject(i);
                var addrInfo = link.optJSONArray("addr_info");
                if (addrInfo == null) continue;
                for (int j = 0; j < addrInfo.length(); j++) {
                    var ai = addrInfo.getJSONObject(j);
                    var local = ai.optString("local", "");
                    var prefixLen = ai.optInt("prefixlen", 0);
                    if (!local.isEmpty())
                        addr.put(fmt("%s/%d", local, prefixLen));
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, fmt("Failed to parse addresses of %s", dev), e);
        }
        return addr;
    }

    @NonNull
    @Override
    public JSONArray listBridges() {
        var result = runListQuiet("ip", "-j", "link", "show", "type", "bridge");
        var bridges = new JSONArray();
        if (!result.isSuccess()) return bridges;
        try {
            var arr = new JSONArray(result.getOutString());
            for (int i = 0; i < arr.length(); i++) {
                var link = arr.getJSONObject(i);
                var br = new JSONObject();
                var name = link.optString("ifname", "");
                br.put("name", name);
                br.put("state", link.optString("operstate", "UNKNOWN").toUpperCase());
                br.put("mtu", link.optInt("mtu", 1500));
                br.put("interfaces", listBridgeInterfaces(name));
                bridges.put(br);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse bridge list", e);
        }
        return bridges;
    }

    @NonNull
    @Override
    public JSONArray listBridgeInterfaces(@NonNull String bridge) {
        var members = new JSONArray();
        var result = runListQuiet("ip", "-j", "link", "show", "master", bridge);
        if (!result.isSuccess()) return members;
        try {
            var arr = new JSONArray(result.getOutString());
            for (int i = 0; i < arr.length(); i++) {
                var link = arr.getJSONObject(i);
                members.put(link.optString("ifname", ""));
            }
        } catch (JSONException e) {
            Log.e(TAG, fmt("Failed to parse interfaces of %s", bridge), e);
        }
        return members;
    }

    @NonNull
    @Override
    public JSONArray listAvailableInterfaces() {
        var iface = new JSONArray();
        var result = runListQuiet("ip", "-j", "link", "show");
        if (!result.isSuccess()) return iface;
        try {
            var arr = new JSONArray(result.getOutString());
            for (int i = 0; i < arr.length(); i++) {
                var link = arr.getJSONObject(i);
                var name = link.optString("ifname", "");
                if (name.equals("lo")) continue;
                var info = link.optJSONObject("linkinfo");
                if (info != null && info.optString("info_kind", "").equals("bridge"))
                    continue;
                var obj = new JSONObject();
                obj.put("name", name);
                obj.put("state", link.optString("operstate", "UNKNOWN").toUpperCase());
                obj.put("master", link.optString("master", ""));
                iface.put(obj);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse interface list", e);
        }
        return iface;
    }

    @NonNull
    @Override
    public JSONArray listInterfaces(VMInstanceStore vms, String bridge) {
        var iFaces = new JSONArray();
        try {
            var result = runListQuiet("ip", "-j", "link", "show", "master", bridge);
            if (!result.isSuccess()) {
                Log.w(TAG, fmt("Failed to list neighbors for bridge %s", bridge));
                result.printLog("ip-neigh");
                return iFaces;
            }
            var data = result.getOutString();
            if (data.isEmpty()) return iFaces;
            var arr = new JSONArray(data);
            for (int i = 0; i < arr.length(); i++) {
                var link = arr.getJSONObject(i);
                var obj = new JSONObject();
                var ifname = link.optString("ifname", "");
                obj.put("name", ifname);
                obj.put("address", link.optString("address", ""));
                obj.put("state", link.optString("operstate", "UNKNOWN"));
                var vmInfo = vms.findVMByTap(ifname);
                if (vmInfo != null) {
                    obj.put("vm_id", vmInfo.optString("vm_id", ""));
                    obj.put("vm_name", vmInfo.optString("vm_name", ""));
                }
                iFaces.put(obj);
            }
        } catch (Exception e) {
            Log.w(TAG, fmt("Error while list interfaces for bridge %s", bridge), e);
        }
        return iFaces;
    }

    @NonNull
    @Override
    public JSONArray listNeighbors(String bridge) {
        var neighbors = new JSONArray();
        try {
            var result = runListQuiet("ip", "neigh", "show", "dev", bridge);
            if (!result.isSuccess()) {
                Log.w(TAG, fmt("Failed to list neighbors for bridge %s", bridge));
                result.printLog("ip-neigh");
                return neighbors;
            }
            var data = result.getOutString().trim();
            if (data.isEmpty()) return neighbors;
            for (var line : data.split("\n")) {
                var parts = line.trim().split("\\s+");
                if (parts.length < 1) continue;
                var obj = new JSONObject();
                obj.put("dst", parts[0]);
                for (int i = 1; i < parts.length - 1; i++) {
                    if (parts[i].equals("lladdr"))
                        obj.put("lladdr", parts[++i]);
                    else if (parts[i].equals("dev"))
                        obj.put("dev", parts[++i]);
                }
                obj.put("state", List.of(parts[parts.length - 1]));
                neighbors.put(obj);
            }
        } catch (Exception e) {
            Log.w(TAG, fmt("Error while list neighbors for bridge %s", bridge), e);
        }
        return neighbors;
    }

    @NonNull
    @Override
    public JSONArray listDhcpLeases(String bridge) {
        var leases = new JSONArray();
        var leaseFile = new File(Dnsmasq.getDnsmasqLeaseFile(bridge));
        if (!leaseFile.exists()) {
            Log.w(TAG, fmt("DHCP lease file not found for bridge %s: %s", bridge, leaseFile));
            return leases;
        }
        try {
            var leaseResult = readFile(leaseFile);
            for (var line : leaseResult.split("\n")) {
                if (line.trim().isEmpty()) continue;
                var parts = line.trim().split("\\s+");
                if (parts.length >= 4) {
                    var lease = new JSONObject();
                    lease.put("expires", parts[0]);
                    lease.put("mac", parts[1]);
                    lease.put("ip", parts[2]);
                    lease.put("hostname", parts.length >= 5 ? parts[3] : "");
                    leases.put(lease);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, fmt("Error while read DHCP leases %s", leaseFile), e);
        }
        return leases;
    }

    @NonNull
    public Set<String> listRouteTables(boolean ipv6) {
        var set = new LinkedHashSet<String>();
        var result = ipv6
            ? runListQuiet("ip", "-6", "rule", "show")
            : runListQuiet("ip", "rule", "show");
        if (!result.isSuccess()) {
            Log.w(TAG, "Failed to list IP rules");
            result.printLog("ip-rule");
            return set;
        }
        var data = result.getOutString();
        for (var line : data.split("\n")) {
            var idx = line.indexOf(" lookup ");
            if (idx < 0) continue;
            set.add(line.substring(idx + (" lookup ".length())).trim());
        }
        return set;
    }

    /** Adds one subnet to every policy routing table so host replies route back. */
    public void populateRouteForNetwork(@NonNull String dev, @NonNull IPNetwork<?, ?, ?> net) {
        boolean ipv6 = net instanceof IPv6Network;
        var spec = net.toNetworkString();
        for (var table : listRouteTables(ipv6)) {
            if (ipv6)
                runList("ip", "-6", "route", "add", spec, "dev", dev, "table", table);
            else
                runList("ip", "route", "add", spec, "dev", dev, "table", table);
        }
    }

    @Override
    public void populateRuleRoute(@NonNull NetworkInstance inst) {
        var br = inst.getBridgeName();
        if (br == null) return;
        for (var vlan : inst.getVlans()) {
            var dev = vlan.isUntagged() ? br : vlanSubInterface(br, vlan.getVlanId());
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
