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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.network.backend.BridgeBackend;
import cn.classfun.droidvm.daemon.network.backend.ManagedProcess;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
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
    private GvswitchClient client = null;
    /** Set once stop() runs so the watchdog never relaunches a torn-down switch. */
    private volatile boolean stopped = false;
    /** tapName -> NIC config of currently attached ports. */
    private final Map<String, VMNicConfig> attached = new ConcurrentHashMap<>();
    /** tapName -> installed gvswitch forward refs ("vlan/id"). */
    private final Map<String, List<String>> installedForwards = new ConcurrentHashMap<>();
    /** tapName -> human-readable forward install failures (e.g. port in use). */
    private final Map<String, List<String>> forwardFailures = new ConcurrentHashMap<>();

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
    public synchronized void start() throws Exception {
        launch();
    }

    /** (Re)writes the config, spawns gvswitch and waits for its API. */
    private synchronized void launch() throws Exception {
        var bytes = new byte[16];
        random.nextBytes(bytes);
        var sb = new StringBuilder();
        for (var b : bytes) sb.append(fmt("%02x", b));
        var token = sb.toString();
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

    /**
     * Watchdog tick: gvswitch IS the data path here, so if it died the whole
     * network is down. Relaunch it and re-attach every port that was attached
     * -- the taps are daemon-owned and still exist (crosvm holds them open), so
     * we only re-POST the af_xdp ports + their static leases and forwards; the
     * taps are never touched. Dynamic gvswitch state (learned FDB, DHCP leases)
     * is rebuilt by the guests reconnecting through the restored switch.
     */
    @Override
    public synchronized void reconcile() {
        if (stopped || process.isRunning()) return;
        var nics = new LinkedHashMap<>(attached);
        Log.w(TAG, fmt(
            "gvswitch %s is down (exit=%d), restarting and re-attaching %d port(s)",
            id8, process.getExitCode(), nics.size()
        ));
        // The previous process's installs are gone; rebuild them from scratch.
        installedForwards.clear();
        forwardFailures.clear();
        try {
            launch();
        } catch (Exception e) {
            Log.e(TAG, fmt("gvswitch %s relaunch failed", id8), e);
            return;
        }
        for (var e : nics.entrySet()) {
            try {
                postPort(e.getValue(), e.getKey());
            } catch (Exception ex) {
                Log.w(TAG, fmt("Re-attach of port %s failed", e.getKey()), ex);
            }
        }
        Log.i(TAG, fmt("gvswitch %s recovered", id8));
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
            //noinspection BusyWait
            Thread.sleep(200);
        }
        throw new RuntimeException("gvswitch API did not become ready", last);
    }

    @Override
    public synchronized void stop() {
        stopped = true;
        attached.clear();
        installedForwards.clear();
        forwardFailures.clear();
        process.stop();
        var sock = new File(socketPath());
        if (sock.exists() && !sock.delete())
            Log.w(TAG, fmt("Failed to remove socket %s", sock));
        client = null;
    }

    @Override
    public synchronized void attachNic(@NonNull VMNicConfig nic, @NonNull String tapName) throws Exception {
        if (client == null) throw new IllegalStateException("gvswitch not running");
        var net = inst.getStore().backend;
        if (net.isInterfaceExists(tapName)) net.deleteTap(tapName);
        if (!net.createTap(tapName))
            throw new RuntimeException(fmt("Failed to create TAP %s", tapName));
        try {
            if (!net.setLinkState(tapName, true))
                throw new RuntimeException(fmt("Failed to bring up TAP %s", tapName));
            postPort(nic, tapName);
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

    /**
     * Creates the gvswitch af_xdp port over the (already existing, up) tap plus
     * its static leases and forwards. Never creates or deletes the tap, so it
     * is reusable for watchdog re-attach without disturbing crosvm.
     */
    @SuppressWarnings("ExtractMethodRecommender")
    private void postPort(@NonNull VMNicConfig nic, @NonNull String tapName) throws Exception {
        if (client == null) throw new IllegalStateException("gvswitch not running");
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
        installNicForwards(nic, tapName);
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

    /**
     * Installs this NIC's port forwards one at a time so a single bind
     * failure (host port already in use -- gvswitch local forwards listen
     * on the host) only drops that one rule. Successfully installed refs
     * are recorded per tap so detach removes exactly what this NIC added;
     * failures are surfaced via network_info.
     */
    private void installNicForwards(@NonNull VMNicConfig nic, @NonNull String tapName) {
        var ids = new ArrayList<String>();
        var failures = new ArrayList<String>();
        var vlan = nic.resolveDhcpVlan(inst);
        if (vlan != null) {
            if (nic.isDhcp4LeaseEnabled() && vlan.isDhcp4Enabled() && vlan.isIpv4Snat()) {
                var net4 = vlan.getIpv4Network();
                if (net4 != null) {
                    var guestIp = guestIpAtOffset(net4, nic.getDhcp4Offset());
                    if (guestIp != null)
                        for (var fwd : nic.getDhcp4Forwards())
                            postForward(vlan.getVlanId(), fwd, guestIp, false, ids, failures);
                }
            }
            if (nic.isDhcp6LeaseEnabled() && vlan.isDhcp6Enabled() && vlan.isIpv6Snat()) {
                var net6 = vlan.getIpv6Network();
                if (net6 != null) {
                    var guestIp = guestIpAtOffset(net6, nic.getDhcp6Offset());
                    if (guestIp != null)
                        for (var fwd : nic.getDhcp6Forwards())
                            postForward(vlan.getVlanId(), fwd, guestIp, true, ids, failures);
                }
            }
        }
        if (!ids.isEmpty()) installedForwards.put(tapName, ids);
        if (!failures.isEmpty()) {
            forwardFailures.put(tapName, failures);
            Log.w(TAG, fmt("Forward install failures on %s: %s", tapName, failures));
        }
    }

    @Nullable
    private static String guestIpAtOffset(
        @NonNull IPv4Network net, long offset
    ) {
        try {
            return net.addressAtOffset(offset).toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String guestIpAtOffset(
        @NonNull IPv6Network net, long offset
    ) {
        try {
            return net.addressAtOffset(BigInteger.valueOf(offset)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * POSTs each concrete (proto, port) of one forward; gvswitch takes
     * single ports, so ranges are expanded (capped). Each accepted rule's
     * "vlan/id" ref is recorded; rejections (e.g. bind in use) are noted.
     */
    private void postForward(
        int vlanId, @NonNull VMNicConfig.PortForward fwd, @NonNull String guestIp,
        boolean v6, @NonNull List<String> ids, @NonNull List<String> failures
    ) {
        try {
            fwd.validate();
        } catch (Exception e) {
            failures.add(fmt("%s %s:%s (%s)", fwd.proto, fwd.host, fwd.guest, e.getMessage()));
            return;
        }
        int count = fwd.hostEnd() - fwd.hostStart() + 1;
        if (count > MAX_FORWARD_EXPANSION) {
            Log.w(TAG, fmt(
                "Forward range %s exceeds %d ports on a gVisor bridge, truncating",
                fwd.host, MAX_FORWARD_EXPANSION));
            count = MAX_FORWARD_EXPANSION;
        }
        var bindHost = v6 ? "[::]" : "0.0.0.0";
        var guestHost = v6 ? fmt("[%s]", guestIp) : guestIp;
        for (var proto : fwd.protocols()) {
            for (int i = 0; i < count; i++) {
                int hostPort = fwd.hostStart() + i;
                var bind = fmt("%s:%d", bindHost, hostPort);
                try {
                    var rule = new JSONObject();
                    rule.put("type", "local");
                    rule.put("network", proto);
                    rule.put("bind", bind);
                    rule.put("host", fmt("%s:%d", guestHost, fwd.guestStart() + i));
                    var resp = client.post(
                        fmt("/api/v1/gateways/%d/forwards", vlanId), rule);
                    if (resp.isSuccess()) {
                        var id = resp.json().optString("id", "");
                        if (!id.isEmpty()) ids.add(fmt("%d/%s", vlanId, id));
                    } else {
                        failures.add(fmt("%s %s (%s)", proto, bind, briefBody(resp.body)));
                    }
                } catch (Exception e) {
                    failures.add(fmt("%s %s (%s)", proto, bind, e.getMessage()));
                }
            }
        }
    }

    /** Removes exactly the forwards this tap installed. */
    private void removeNicForwards(@NonNull String tapName) {
        forwardFailures.remove(tapName);
        var ids = installedForwards.remove(tapName);
        if (ids == null || client == null) return;
        for (var ref : ids) {
            int slash = ref.indexOf('/');
            if (slash < 0) continue;
            try {
                client.delete(fmt("/api/v1/gateways/%s/forwards/%s",
                    ref.substring(0, slash), ref.substring(slash + 1)));
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to delete forward %s", ref), e);
            }
        }
    }

    @NonNull
    private static String briefBody(@NonNull String body) {
        var b = body.trim();
        return b.length() > 80 ? b.substring(0, 80) : b;
    }

    private static void check(@NonNull GvswitchClient.Response response, @NonNull String what) {
        if (!response.isSuccess()) throw new RuntimeException(fmt(
            "gvswitch %s failed (%d): %s", what, response.code, response.body));
    }

    @Override
    public synchronized void detachNic(@NonNull VMNicConfig nic, @NonNull String tapName) {
        attached.remove(tapName);
        if (client != null) {
            try {
                client.delete(fmt("/api/v1/ports/%s", tapName));
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to delete gvswitch port %s", tapName), e);
            }
            removeStaticLeases(nic, tapName);
            removeNicForwards(tapName);
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

    /**
     * Same three-dimension shape as the Linux bridge for a consistent UI: the
     * gvswitch gateway addresses are the VMM's configured L3 endpoints, so
     * every row is configured and (while running) bound.
     */
    @NonNull
    @Override
    public JSONArray listAddressEntries() {
        var out = new JSONArray();
        if (inst.getUplinkMode() != UplinkMode.L3) return out;
        for (var vlan : inst.getVlans()) {
            int vid = vlan.getVlanId();
            var net4 = vlan.getIpv4Network();
            if (net4 != null) out.put(addressEntry(net4.toString(), vid, true, "configured"));
            var net6 = vlan.getIpv6Network();
            if (net6 != null) out.put(addressEntry(net6.toString(), vid, true, "configured"));
        }
        return out;
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
        obj.put("gvswitch_exit_code", process.getExitCode());
        obj.put("gvswitch_socket", socketPath());
        appendForwardFailures(obj, forwardFailures);
    }

    @NonNull
    @Override
    public JSONArray listTools() {
        var out = new JSONArray();
        out.put(toolEntry("gvswitch", process.isRunning()));
        return out;
    }

    @Nullable
    @Override
    public List<String> toolLog(@NonNull String key) {
        return key.equals("gvswitch") ? process.getLog() : null;
    }
}
