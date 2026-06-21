package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.network.backend.gvisor.GvswitchClient;
import cn.classfun.droidvm.daemon.network.backend.pd.Duid;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.network.Ipv6Source;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.utils.NetUtils;

/**
 * DHCPv4/DHCPv6/RA (and DHCPv6-PD) service for one Linux-bridge network,
 * backed by the bridgedhcp helper: one process per network serving every
 * per-VLAN bridge. Pools and static leases are offset-based, so a PD
 * renumbering needs no config rewrite; static leases are pushed through
 * the unix-socket API instead of dnsmasq's hostsfile/SIGHUP dance, and PD
 * prefix changes flow back as JSON event lines on the child's stdout.
 */
public final class BridgeDhcp {
    private static final String TAG = "BridgeDhcp";
    private static final String[] DEFAULT_DNS4 = {"8.8.8.8", "1.1.1.1"};
    private static final String[] DEFAULT_DNS6 = {"2001:4860:4860::8888"};
    private static final SecureRandom random = new SecureRandom();

    /** PD delegation events; address is null when the prefix was lost. */
    public interface PdListener {
        void onPdAddressChanged(int vlanId, @Nullable IPv6Network address);
    }

    private final NetworkInstance inst;
    private final String br;
    private final ManagedProcess process;
    private final PdListener pdListener;
    private final String apiKey;
    private volatile boolean active = false;
    private GvswitchClient client = null;

    public BridgeDhcp(@NonNull NetworkInstance inst, @Nullable PdListener pdListener) {
        this.inst = inst;
        this.br = inst.item.optString("bridge_name", "");
        this.pdListener = pdListener;
        this.process = new ManagedProcess("bridgedhcp", br);
        var key = new byte[24];
        random.nextBytes(key);
        var sb = new StringBuilder();
        for (var b : key) sb.append(fmt("%02x", b));
        this.apiKey = sb.toString();
    }

    /** True when any VLAN needs DHCPv4, DHCPv6, RA or a PD client. */
    public static boolean isNeeded(@NonNull List<VlanConfig> vlans) {
        for (var vlan : vlans) {
            if (vlan.isDhcp4Enabled() || vlan.isDhcp6Enabled() || vlan.isSlaacEnabled())
                return true;
            if (vlan.getIpv6Source() == Ipv6Source.DHCP_PD && vlan.getPdUplink() != null)
                return true;
        }
        return false;
    }

    public boolean start() {
        active = true;
        try {
            writeKeyFile();
            writeConf();
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to write bridgedhcp config for %s", br), e);
            return false;
        }
        client = new GvswitchClient(getSocketFile(br), apiKey);
        process.setLineListener(this::onEventLine);
        var args = new ArrayList<String>();
        args.add(getAssetBinaryPath("bridgedhcp"));
        args.add("--config");
        args.add(getConfFile(br));
        return process.start(args);
    }

    public void stop() {
        active = false;
        process.stop();
        client = null;
    }

    /**
     * Restarts bridgedhcp if it died. The helper reloads its persisted state
     * file on start, so active DHCP leases and PD delegations survive the
     * restart; config and static leases are rebuilt deterministically. Called
     * on the watchdog's 5s tick while the network is running. Returns true when
     * healthy.
     */
    public synchronized boolean reconcile() {
        if (!active || process.isRunning()) return true;
        Log.w(TAG, fmt(
            "bridgedhcp for %s is down (exit=%d), restarting", br, process.getExitCode()
        ));
        return restart();
    }

    /**
     * Rewrites the config (re-resolving PD uplinks) and restarts the helper.
     * Used when a PD uplink that was unavailable at start later appears, so
     * the now-resolvable interface gets a PD client. Persisted leases and PD
     * delegations survive via the state file.
     */
    public synchronized boolean restart() {
        stop();
        return start();
    }

    public boolean isRunning() {
        return process.isRunning();
    }

    public int getExitCode() {
        return process.getExitCode();
    }

    @NonNull
    public List<String> getLog() {
        return process.getLog();
    }

    /** Child stdout: single-line JSON events (logs go to stderr). */
    private void onEventLine(@NonNull String line) {
        if (!line.startsWith("{")) return;
        try {
            var ev = new JSONObject(line);
            var type = ev.optString("event", "");
            if (!type.equals("pd_prefix") && !type.equals("pd_lost")) return;
            if (pdListener == null) return;
            int vlanId = Integer.parseInt(ev.optString("tag", "-1"));
            if (vlanId < 0) return;
            IPv6Network address = null;
            if (type.equals("pd_prefix"))
                address = IPv6Network.parse(ev.getString("address"));
            pdListener.onPdAddressChanged(vlanId, address);
        } catch (Exception e) {
            Log.w(TAG, fmt("Bad bridgedhcp event: %s", line), e);
        }
    }

    /** Pushes the current static lease set (both families) for every VLAN. */
    public void reloadStaticLeases() {
        var c = client;
        if (c == null) return;
        for (var vlan : inst.getVlans()) {
            var dev = LinuxNetwork.vlanDevice(br, vlan.getVlanId());
            try {
                var statics = buildStatics(vlan);
                c.put(fmt("/v1/ifaces/%s/statics/4", dev),
                    new JSONObject().put("statics", statics[0]));
                c.put(fmt("/v1/ifaces/%s/statics/6", dev),
                    new JSONObject().put("statics", statics[1]));
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to push static leases for %s", dev), e);
            }
        }
    }

    /** Active leases of every VLAN in the legacy dnsmasq-derived shape. */
    @NonNull
    public JSONArray getLeases() {
        var out = new JSONArray();
        var c = client;
        if (c == null) return out;
        for (var vlan : inst.getVlans()) {
            var dev = LinuxNetwork.vlanDevice(br, vlan.getVlanId());
            for (var family : new int[]{4, 6}) {
                try {
                    var resp = c.get(fmt("/v1/ifaces/%s/leases/%d", dev, family));
                    if (!resp.isSuccess()) continue;
                    var leases = resp.json().optJSONArray("leases");
                    if (leases == null) continue;
                    for (int i = 0; i < leases.length(); i++) {
                        var l = leases.optJSONObject(i);
                        if (l == null) continue;
                        var obj = new JSONObject();
                        obj.put("expires", parseExpiry(l.optString("expiry", "")));
                        obj.put("mac", l.optString("mac", ""));
                        obj.put("ip", l.optString("ip", ""));
                        obj.put("hostname", l.optString("hostname", ""));
                        out.put(obj);
                    }
                } catch (Exception e) {
                    Log.w(TAG, fmt("Failed to list leases for %s/%d", dev, family), e);
                }
            }
        }
        return out;
    }

    private static long parseExpiry(@NonNull String rfc3339) {
        try {
            return OffsetDateTime.parse(rfc3339).toEpochSecond();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Full status document of the helper (for network info). */
    @Nullable
    public JSONObject getStatus() {
        var c = client;
        if (c == null) return null;
        try {
            var resp = c.get("/v1/status");
            if (resp.isSuccess()) return resp.json();
        } catch (Exception e) {
            Log.w(TAG, "Failed to query bridgedhcp status", e);
        }
        return null;
    }

    /**
     * Per-VLAN DHCPv6-PD client state: {@code [{vlan, state, prefix}]} for
     * every served interface that runs a PD client (empty prefix while still
     * searching). Derived from the helper's {@code /v1/status} document.
     */
    @NonNull
    public JSONArray getPdStatus() {
        var out = new JSONArray();
        var status = getStatus();
        if (status == null) return out;
        var ifaces = status.optJSONArray("ifaces");
        if (ifaces == null) return out;
        for (int i = 0; i < ifaces.length(); i++) {
            var iface = ifaces.optJSONObject(i);
            if (iface == null) continue;
            var state = iface.optString("pd_state", "");
            if (state.isEmpty()) continue; // no PD client on this interface
            try {
                out.put(new JSONObject()
                    .put("vlan", Integer.parseInt(iface.optString("tag", "-1")))
                    .put("state", state)
                    // the raw delegation P/L (context) ...
                    .put("prefix", iface.optString("pd_prefix", ""))
                    // ... and the composed host address P::1/L actually bound
                    // on the bridge -- what the UI shows and what the address
                    // union deduplicates against.
                    .put("address", iface.optString("pd_address", "")));
            } catch (Exception e) {
                Log.w(TAG, "Bad PD status entry", e);
            }
        }
        return out;
    }

    /** Forces a PD renew (RENEW/REBIND, same prefix) on one VLAN's client. */
    public boolean renewPd(int vlanId) {
        return pdAction(vlanId, "renew");
    }

    /** Releases and re-solicits the PD delegation on one VLAN's client. */
    public boolean releasePd(int vlanId) {
        return pdAction(vlanId, "release");
    }

    private boolean pdAction(int vlanId, @NonNull String action) {
        var c = client;
        if (c == null) return false;
        var dev = LinuxNetwork.vlanDevice(br, vlanId);
        try {
            return c.post(fmt("/v1/ifaces/%s/pd/%s", dev, action), null).isSuccess();
        } catch (Exception e) {
            Log.w(TAG, fmt("PD %s failed for %s", action, dev), e);
            return false;
        }
    }

    private void writeKeyFile() throws Exception {
        var file = new File(getKeyFile(br));
        var parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new RuntimeException(fmt("Failed to create directory %s", parent));
        try (var writer = new FileWriter(file)) {
            writer.write(apiKey);
        }
        if (!file.setReadable(false, false) || !file.setReadable(true, true))
            Log.w(TAG, "Failed to restrict key file permissions");
    }

    private void writeConf() throws Exception {
        var cfg = new JSONObject();
        cfg.put("api_socket", getSocketFile(br));
        cfg.put("api_key_file", getKeyFile(br));
        cfg.put("state_file", getStateFile(br));
        var ifaces = new JSONArray();
        for (var vlan : inst.getVlans())
            ifaces.put(buildIface(vlan));
        cfg.put("interfaces", ifaces);
        var file = new File(getConfFile(br));
        try (var writer = new FileWriter(file)) {
            writer.write(cfg.toString(2));
        }
    }

    @NonNull
    private JSONObject buildIface(@NonNull VlanConfig vlan) throws Exception {
        var obj = new JSONObject();
        obj.put("name", LinuxNetwork.vlanDevice(br, vlan.getVlanId()));
        obj.put("tag", String.valueOf(vlan.getVlanId()));
        // Served prefixes are authoritative config -- bridgedhcp no longer
        // derives the pools/SLAAC prefix from the bridge's live addresses.
        // v6 is sent only for a static source; DHCP-PD drives it dynamically.
        var cidr4 = vlan.getIpv4Cidr();
        if (cidr4 != null) obj.put("prefix4", cidr4);
        if (vlan.getIpv6Source() == Ipv6Source.STATIC) {
            var cidr6 = vlan.getIpv6Cidr();
            if (cidr6 != null) obj.put("prefix6", cidr6);
        }
        if (vlan.isDhcp4Enabled()) {
            var dns = new JSONArray();
            for (var s : vlan.getDnsServers())
                if (!s.contains(":")) dns.put(s);
            if (dns.length() == 0)
                for (var s : DEFAULT_DNS4) dns.put(s);
            obj.put("dhcp4", new JSONObject()
                .put("pool_offset_start", vlan.getDhcp4OffsetStart())
                .put("pool_offset_end", vlan.getDhcp4OffsetEnd())
                .put("lease_time", "12h")
                .put("dns", dns));
        }
        if (vlan.isDhcp6Enabled()) {
            var dns = new JSONArray();
            for (var s : vlan.getDnsServers())
                if (s.contains(":")) dns.put(s);
            if (dns.length() == 0)
                for (var s : DEFAULT_DNS6) dns.put(s);
            obj.put("dhcp6", new JSONObject()
                .put("pool_offset_start", vlan.getDhcp6OffsetStart())
                .put("pool_offset_end", vlan.getDhcp6OffsetEnd())
                .put("lease_time", "12h")
                .put("dns", dns));
        }
        if (vlan.isSlaacEnabled()) obj.put("slaac", true);
        if (vlan.getIpv6Source() == Ipv6Source.DHCP_PD && vlan.getPdUplink() != null) {
            var uplink = UplinkResolver.resolve(vlan.getPdUplink());
            if (uplink == null) {
                Log.w(TAG, fmt(
                    "PD uplink \"%s\" not found, skipping PD for vlan %d",
                    vlan.getPdUplink(), vlan.getVlanId()
                ));
            } else {
                var crc = new CRC32();
                crc.update(inst.getId().toString().getBytes());
                crc.update(vlan.getVlanId());
                // CRC32 is an unsigned 32-bit value; write it as-is. Casting
                // to int makes it negative when bit 31 is set, and bridgedhcp
                // parses "iaid" into a Go uint32, which rejects a negative
                // number -- the whole helper then fails to start.
                // No prefix_len: bridgedhcp binds the host at the delegation's
                // own length L (suffix ::1), and gates SLAAC/DHCPv6 on L.
                obj.put("pd", new JSONObject()
                    .put("uplink", uplink)
                    .put("duid", Duid.format(resolvePdDuid(vlan)))
                    .put("iaid", crc.getValue())
                    .put("suffix", "::1")
                    .put("route_table", resolvePdRouteTable(vlan)));
            }
        }
        var statics = buildStatics(vlan);
        if (statics[0].length() > 0) obj.put("statics4", statics[0]);
        if (statics[1].length() > 0) obj.put("statics6", statics[1]);
        return obj;
    }

    /**
     * Builds the [v4, v6] static binding arrays of one VLAN from the VM NIC
     * configs (mac -> offset, matching the NIC's DHCP lease settings).
     */
    @NonNull
    private JSONArray[] buildStatics(@NonNull VlanConfig vlan) throws Exception {
        var v4 = new JSONArray();
        var v6 = new JSONArray();
        var netId = inst.getId().toString();
        var vms = inst.getStore().context.getVMs();
        if (vms == null) return new JSONArray[]{v4, v6};
        vms.forEach((vmId, vm) -> {
            final int[] idx = {0};
            var failed = new Exception[1];
            vm.forEachNic(nic -> {
                int nicIdx = idx[0]++;
                try {
                    if (!netId.equals(nic.getNetworkId())) return;
                    var mac = nic.getMacAddress();
                    if (mac == null) return;
                    var nicVlan = nic.resolveDhcpVlan(inst);
                    if (nicVlan == null || nicVlan.getVlanId() != vlan.getVlanId()) return;
                    var id = fmt("%s-%d", vmId, nicIdx);
                    if (nic.isDhcp4LeaseEnabled() && vlan.isDhcp4Enabled()) {
                        v4.put(new JSONObject()
                            .put("id", id)
                            .put("mac", mac)
                            .put("offset", nic.getDhcp4Offset()));
                    }
                    if (nic.isDhcp6LeaseEnabled() && vlan.isDhcp6Enabled()) {
                        v6.put(new JSONObject()
                            .put("id", id)
                            .put("mac", mac)
                            .put("offset", nic.getDhcp6Offset()));
                    }
                } catch (Exception e) {
                    failed[0] = e;
                }
            });
            if (failed[0] != null)
                Log.w(TAG, fmt("Bad static lease config for VM %s", vmId), failed[0]);
        });
        return new JSONArray[]{v4, v6};
    }

    /** First id of the range handed out to bridgedhcp PD route tables. */
    private static final long PD_ROUTE_TABLE_BASE = 9991;
    /** Serializes allocations across concurrently starting networks. */
    private static final Object routeTableLock = new Object();

    /**
     * The PD routing table of one VLAN: the persisted allocation when
     * present, else the lowest id from PD_ROUTE_TABLE_BASE upward that no
     * VLAN of any network (running or stopped) holds, persisted
     * immediately. Stability matters: the id is bridgedhcp's crash-cleanup
     * key -- a bridge that comes back under a different id would leave the
     * previous run's rules behind, and reusing a *live* network's id would
     * wipe its rules at startup.
     */
    private long resolvePdRouteTable(@NonNull VlanConfig vlan) {
        synchronized (routeTableLock) {
            var existing = vlan.getPdRouteTable();
            if (existing != 0) return existing;
            var used = new java.util.HashSet<Long>();
            inst.getStore().forEach((id, other) -> {
                for (var v : other.getVlans())
                    used.add(v.getPdRouteTable());
            });
            var table = PD_ROUTE_TABLE_BASE;
            while (used.contains(table)) table++;
            vlan.setPdRouteTable(table);
            Log.i(TAG, fmt("Allocated PD route table %d for %s vlan %d",
                table, br, vlan.getVlanId()));
            return table;
        }
    }

    /**
     * The PD DUID: the configured one, else a generated DUID-LL persisted
     * back into the VLAN config so the delegation survives restarts.
     */
    @NonNull
    private byte[] resolvePdDuid(@NonNull VlanConfig vlan) {
        var configured = vlan.getPdDuid();
        if (configured != null && !configured.isEmpty()) {
            try {
                return Duid.parse(configured);
            } catch (Exception e) {
                Log.w(TAG, fmt("Invalid DUID \"%s\", generating one", configured), e);
            }
        }
        var mac = inst.getBridgeMacAddress();
        if (mac == null) mac = inst.item.optString("generated_mac", "");
        if (mac == null || mac.isEmpty()) mac = NetUtils.generateRandomMac();
        var duid = Duid.fromLinkLayer(mac);
        vlan.setPdDuid(Duid.format(duid));
        return duid;
    }

    @NonNull
    public static String getConfFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("bridgedhcp-%s.json", br));
    }

    @NonNull
    public static String getKeyFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("bridgedhcp-%s.key", br));
    }

    @NonNull
    public static String getSocketFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("bridgedhcp-%s.sock", br));
    }

    @NonNull
    public static String getStateFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("bridgedhcp-%s.state.json", br));
    }
}
