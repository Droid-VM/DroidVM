package cn.classfun.droidvm.daemon.network.backend;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;
import cn.classfun.droidvm.lib.network.IPNetwork;
import cn.classfun.droidvm.lib.network.IPv4Network;

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
    private Set<String> listRouteTables() {
        var set = new LinkedHashSet<String>();
        var result = runListQuiet("ip", "rule", "show");
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

    @Override
    public void populateRuleRoute(@NonNull NetworkInstance inst) {
        var br = inst.item.optString("bridge_name", "");
        var list = listRouteTables();
        inst.item.get("ipv4_addresses").forEachArray(a -> {
            try {
                var addr = IPv4Network.parse(a.asString());
                var net = addr.network();
                for (var table : list)
                    runList("ip", "route", "add", net.toString(), "dev", br, "table", table);
            } catch (Exception ignored) {
            }
        });
    }
}
