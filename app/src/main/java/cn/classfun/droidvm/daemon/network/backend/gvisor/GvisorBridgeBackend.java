package cn.classfun.droidvm.daemon.network.backend.gvisor;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.network.backend.BridgeBackend;
import cn.classfun.droidvm.daemon.network.backend.ManagedProcess;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.store.vm.VMNicConfig;

/**
 * gVisor vswitch data path: one gvswitch process per network listening on
 * a private unix socket. VM NICs are tap devices that gvswitch takes over
 * via AF_XDP; per-VLAN gateways inside gvswitch provide DHCP, SLAAC, SNAT
 * (including IPv6) and port forwards.
 */
public final class GvisorBridgeBackend extends BridgeBackend {
    private static final String TAG = "GvisorBridgeBackend";
    /** gvswitch forwards take single ports; ranges are expanded with this cap. */
    private static final int MAX_FORWARD_EXPANSION = 128;
    private static final SecureRandom random = new SecureRandom();
    private final ManagedProcess process;
    private final String id8;
    private String token = null;
    private GvswitchClient client = null;
    /** tapName -> NIC config of currently attached ports. */
    private final Map<String, VMNicConfig> attached = new ConcurrentHashMap<>();

    public GvisorBridgeBackend(@NonNull NetworkInstance inst) {
        super(inst);
        this.id8 = inst.getId().toString().substring(0, 8);
        this.process = new ManagedProcess("gvswitch", id8);
    }

    @NonNull
    private String socketPath() {
        return pathJoin(DATA_DIR, "run", fmt("gvswitch-%s.sock", id8));
    }

    @NonNull
    private String configPath() {
        return pathJoin(DATA_DIR, "run", fmt("gvswitch-%s.json", id8));
    }

    @Override
    public void start() throws Exception {
        var bytes = new byte[16];
        random.nextBytes(bytes);
        var sb = new StringBuilder();
        for (var b : bytes) sb.append(fmt("%02x", b));
        token = sb.toString();
        var config = GvswitchConfigBuilder.build(inst);
        var configFile = new File(configPath());
        var parent = configFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new RuntimeException(fmt("Failed to create %s", parent));
        try (var writer = new FileWriter(configFile)) {
            writer.write(config.toString(2));
        }
        var sock = new File(socketPath());
        if (sock.exists() && !sock.delete())
            Log.w(TAG, fmt("Failed to remove stale socket %s", sock));
        var args = new ArrayList<String>();
        args.add(getAssetBinaryPath("gvswitch"));
        args.add("-listen");
        args.add(socketPath());
        args.add("-auth-token");
        args.add(token);
        args.add("-config");
        args.add(configPath());
        if (inst.isStp()) args.add("-stp");
        if (!process.start(args))
            throw new RuntimeException("Failed to start gvswitch");
        client = new GvswitchClient(socketPath(), token);
        waitReady();
    }

    private void waitReady() throws Exception {
        var deadline = System.currentTimeMillis() + 5000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isRunning())
                throw new RuntimeException(fmt(
                    "gvswitch exited during startup (code %d)", process.getExitCode()));
            try {
                var response = client.get("/api/v1/ports");
                if (response.isSuccess()) return;
                last = new RuntimeException(fmt(
                    "gvswitch API returned %d: %s", response.code, response.body));
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(200);
        }
        throw new RuntimeException("gvswitch API did not become ready", last);
    }

    @Override
    public void stop() {
        attached.clear();
        process.stop();
        var sock = new File(socketPath());
        if (sock.exists() && !sock.delete())
            Log.w(TAG, fmt("Failed to remove socket %s", sock));
        client = null;
    }

    @Override
    public void attachNic(@NonNull VMNicConfig nic, @NonNull String tapName) throws Exception {
        if (client == null) throw new IllegalStateException("gvswitch not running");
        var net = inst.getStore().backend;
        if (net.isInterfaceExists(tapName)) net.deleteTap(tapName);
        if (!net.createTap(tapName))
            throw new RuntimeException(fmt("Failed to create TAP %s", tapName));
        try {
            if (!net.setLinkState(tapName, true))
                throw new RuntimeException(fmt("Failed to bring up TAP %s", tapName));
            var port = new JSONObject();
            port.put("identifier", tapName);
            var vlanId = nic.getVlanId();
            port.put("vlan", vlanId == null ? 4095 : vlanId);
            if (nic.isIsolated()) port.put("isolated", true);
            var mac = nic.getMacAddress();
            if (nic.isMacSecurity() && mac != null)
                port.put("port_security", mac);
            port.put("mode", "client");
            port.put("transport", "af_xdp");
            port.put("interface", tapName);
            port.put("bpdu_guard", true);
            var response = client.post("/api/v1/ports", port);
            if (!response.isSuccess())
                throw new RuntimeException(fmt(
                    "gvswitch port create failed (%d): %s", response.code, response.body));
            attached.put(tapName, nic);
            applyStaticLeases(nic, tapName);
            replaceForwards();
        } catch (Exception e) {
            attached.remove(tapName);
            try {
                client.delete(fmt("/api/v1/ports/%s", tapName));
            } catch (Exception ignored) {
            }
            net.deleteTap(tapName);
            throw e;
        }
    }

    private void applyStaticLeases(@NonNull VMNicConfig nic, @NonNull String tapName)
        throws Exception {
        var vlan = nic.resolveDhcpVlan(inst);
        if (vlan == null) return;
        var mac = nic.getMacAddress();
        if (nic.isDhcp4LeaseEnabled() && vlan.isDhcp4Enabled() && mac != null) {
            var net4 = vlan.getIpv4Network();
            if (net4 != null) {
                var binding = new JSONObject();
                binding.put("id", tapName);
                binding.put("mac", mac);
                binding.put("ip",
                    net4.addressAtOffset(nic.getDhcp4Offset()).toString());
                check(client.put(fmt(
                    "/api/v1/gateways/%d/dhcp4/static/%s", vlan.getVlanId(), tapName
                ), binding), "dhcp4 static lease");
            }
        }
        if (nic.isDhcp6LeaseEnabled() && vlan.isDhcp6Enabled()) {
            var net6 = vlan.getIpv6Network();
            if (net6 != null) {
                var binding = new JSONObject();
                binding.put("id", tapName);
                // guest DUIDs are unknown; bind by port (and MAC when present)
                binding.put("port_identifier", tapName);
                if (mac != null) binding.put("mac", mac);
                binding.put("ip", net6.addressAtOffset(
                    BigInteger.valueOf(nic.getDhcp6Offset())).toString());
                check(client.put(fmt(
                    "/api/v1/gateways/%d/dhcp6/static/%s", vlan.getVlanId(), tapName
                ), binding), "dhcp6 static lease");
            }
        }
    }

    private void removeStaticLeases(@NonNull VMNicConfig nic, @NonNull String tapName) {
        var vlan = nic.resolveDhcpVlan(inst);
        if (vlan == null || client == null) return;
        try {
            client.delete(fmt(
                "/api/v1/gateways/%d/dhcp4/static/%s", vlan.getVlanId(), tapName));
            client.delete(fmt(
                "/api/v1/gateways/%d/dhcp6/static/%s", vlan.getVlanId(), tapName));
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to remove static leases for %s", tapName), e);
        }
    }

    /** Declaratively rebuilds every gateway's forward set from attached NICs. */
    private void replaceForwards() {
        if (client == null) return;
        var vlanIds = new HashSet<Integer>();
        for (var vlan : inst.getVlans()) vlanIds.add(vlan.getVlanId());
        for (var vlanId : vlanIds) {
            var vlan = inst.findVlan(vlanId);
            if (vlan == null) continue;
            var rules = new JSONArray();
            for (var entry : attached.entrySet()) {
                var nic = entry.getValue();
                var nicVlan = nic.resolveDhcpVlan(inst);
                if (nicVlan == null || nicVlan.getVlanId() != vlanId) continue;
                try {
                    appendNicForwards(rules, nic, vlan);
                } catch (Exception e) {
                    Log.w(TAG, fmt("Skipping forwards of %s", entry.getKey()), e);
                }
            }
            try {
                check(client.put(fmt(
                    "/api/v1/gateways/%d/forwards", vlanId), rules), "forwards");
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to update forwards for VLAN %d", vlanId), e);
            }
        }
    }

    private void appendNicForwards(
        @NonNull JSONArray rules, @NonNull VMNicConfig nic, @NonNull VlanConfig vlan
    ) throws JSONException {
        if (nic.isDhcp4LeaseEnabled() && vlan.isDhcp4Enabled() && vlan.isIpv4Snat()) {
            var net4 = vlan.getIpv4Network();
            if (net4 != null) {
                var guestIp = net4.addressAtOffset(nic.getDhcp4Offset()).toString();
                for (var fwd : nic.getDhcp4Forwards())
                    expandForward(rules, fwd, guestIp, false);
            }
        }
        if (nic.isDhcp6LeaseEnabled() && vlan.isDhcp6Enabled() && vlan.isIpv6Snat()) {
            var net6 = vlan.getIpv6Network();
            if (net6 != null) {
                var guestIp = net6.addressAtOffset(
                    BigInteger.valueOf(nic.getDhcp6Offset())).toString();
                for (var fwd : nic.getDhcp6Forwards())
                    expandForward(rules, fwd, guestIp, true);
            }
        }
    }

    /** gvswitch forwards take single ports, so ranges expand to rules (capped). */
    private void expandForward(
        @NonNull JSONArray rules, @NonNull VMNicConfig.PortForward fwd,
        @NonNull String guestIp, boolean v6
    ) throws JSONException {
        fwd.validate();
        int count = fwd.hostEnd() - fwd.hostStart() + 1;
        if (count > MAX_FORWARD_EXPANSION) {
            Log.w(TAG, fmt(
                "Forward range %s exceeds %d ports on a gVisor bridge, truncating",
                fwd.host, MAX_FORWARD_EXPANSION
            ));
            count = MAX_FORWARD_EXPANSION;
        }
        var bindHost = v6 ? "[::]" : "0.0.0.0";
        var guestHost = v6 ? fmt("[%s]", guestIp) : guestIp;
        for (int i = 0; i < count; i++) {
            var rule = new JSONObject();
            rule.put("type", "local");
            rule.put("network", fwd.proto);
            rule.put("bind", fmt("%s:%d", bindHost, fwd.hostStart() + i));
            rule.put("host", fmt("%s:%d", guestHost, fwd.guestStart() + i));
            rules.put(rule);
        }
    }

    private static void check(@NonNull GvswitchClient.Response response, @NonNull String what) {
        if (!response.isSuccess()) throw new RuntimeException(fmt(
            "gvswitch %s failed (%d): %s", what, response.code, response.body));
    }

    @Override
    public void detachNic(@NonNull VMNicConfig nic, @NonNull String tapName) {
        attached.remove(tapName);
        if (client != null) {
            try {
                client.delete(fmt("/api/v1/ports/%s", tapName));
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to delete gvswitch port %s", tapName), e);
            }
            removeStaticLeases(nic, tapName);
            replaceForwards();
        }
        inst.getStore().backend.deleteTap(tapName);
    }

    @Override
    public JSONArray listAddresses() {
        // gateway addresses live inside gvswitch, not on host interfaces
        var arr = new JSONArray();
        if (inst.getUplinkMode() != UplinkMode.L3) return arr;
        for (var vlan : inst.getVlans()) {
            var net4 = vlan.getIpv4Network();
            if (net4 != null) arr.put(net4.toString());
            var net6 = vlan.getIpv6Network();
            if (net6 != null) arr.put(net6.toString());
        }
        return arr;
    }

    @Override
    public JSONArray listInterfaces(VMInstanceStore vms) {
        var arr = new JSONArray();
        if (client == null) return arr;
        try {
            var response = client.get("/api/v1/ports");
            if (!response.isSuccess()) return arr;
            var ports = response.jsonArray();
            for (int i = 0; i < ports.length(); i++) {
                var port = ports.getJSONObject(i);
                var obj = new JSONObject();
                var name = port.optString("identifier", "");
                obj.put("name", name);
                obj.put("state", port.optBoolean("online", false) ? "UP" : "DOWN");
                obj.put("vlan", port.opt("vlan"));
                if (vms != null) {
                    var vmInfo = vms.findVMByTap(name);
                    if (vmInfo != null) {
                        obj.put("vm_id", vmInfo.optString("vm_id", ""));
                        obj.put("vm_name", vmInfo.optString("vm_name", ""));
                    }
                }
                arr.put(obj);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to list gvswitch ports", e);
        }
        return arr;
    }

    @Override
    public JSONArray listNeighbors() {
        var arr = new JSONArray();
        if (client == null) return arr;
        try {
            var response = client.get("/api/v1/fdb");
            if (!response.isSuccess()) return arr;
            var entries = response.jsonArray();
            for (int i = 0; i < entries.length(); i++) {
                var entry = entries.getJSONObject(i);
                var obj = new JSONObject();
                obj.put("lladdr", entry.optString("mac", ""));
                obj.put("dev", entry.optString("port", ""));
                obj.put("dst", "");
                obj.put("state", List.of(
                    entry.optBoolean("static", false) ? "STATIC" : "LEARNED"));
                arr.put(obj);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to list gvswitch fdb", e);
        }
        return arr;
    }

    @Override
    public JSONArray listDhcpLeases() {
        var arr = new JSONArray();
        if (client == null) return arr;
        for (var vlan : inst.getVlans()) {
            collectLeases(arr, vlan, "dhcp4");
            collectLeases(arr, vlan, "dhcp6");
        }
        return arr;
    }

    private void collectLeases(@NonNull JSONArray out, @NonNull VlanConfig vlan, @NonNull String family) {
        try {
            var response = client.get(fmt(
                "/api/v1/gateways/%d/%s/leases", vlan.getVlanId(), family));
            if (!response.isSuccess()) return;
            var leases = response.jsonArray();
            for (int i = 0; i < leases.length(); i++) {
                var lease = leases.getJSONObject(i);
                var obj = new JSONObject();
                obj.put("ip", lease.optString("ip", ""));
                obj.put("mac", lease.optString("mac", ""));
                obj.put("hostname", lease.optString("port_identifier", ""));
                obj.put("expires", lease.optString("expires_at", ""));
                obj.put("vlan", vlan.getVlanId());
                out.put(obj);
            }
        } catch (Exception e) {
            Log.d(TAG, fmt("No %s leases for VLAN %d", family, vlan.getVlanId()));
        }
    }

    @Override
    public void appendInfo(@NonNull JSONObject obj) throws JSONException {
        obj.put("gvswitch_running", process.isRunning());
        obj.put("gvswitch_exit_code", process.getExitCode());
        obj.put("gvswitch_socket", socketPath());
    }
}
