package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;
import cn.classfun.droidvm.lib.Constants;
import cn.classfun.droidvm.lib.network.IPNetwork;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.network.Ipv6Source;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.store.vm.VMNicConfig;
import cn.classfun.droidvm.lib.utils.NetUtils;

/**
 * Linux-bridge data path: kernel bridge (+ VLAN filtering and per-VLAN
 * 802.1q subinterfaces for L3 mode), bridgedhcp for DHCP/RA/DHCPv6-PD,
 * iptables for SNAT and port forwards, pbridge for Wi-Fi L2 uplinks.
 */
public final class LinuxBridgeBackend extends BridgeBackend {
    private static final String TAG = "LinuxBridgeBackend";
    private final LinuxNetwork net;
    private final FirewallHelper firewall;
    private final String br;
    private BridgeDhcp dhcp = null;
    private Pbridge pbridge = null;
    private String resolvedUplink = null;
    private boolean uplinkEnslaved = false;
    /** vlanId -> gateway-addressed /64 from a live PD delegation. */
    private final Map<Integer, IPv6Network> liveV6 = new ConcurrentHashMap<>();
    private final Map<String, Boolean> macSecurityActive = new ConcurrentHashMap<>();
    /** tapName -> forwards this NIC actually installed (for exact teardown). */
    private final Map<String, java.util.List<InstalledForward>> installedForwards = new ConcurrentHashMap<>();
    /** tapName -> human-readable forward install failures. */
    private final Map<String, java.util.List<String>> forwardFailures = new ConcurrentHashMap<>();
    /** vids whose per-VLAN bridge + trunk leg exist (configured or on-demand). */
    private final java.util.Set<Integer> createdVlanBridges = ConcurrentHashMap.newKeySet();
    /** DHCP-PD vlanId -> uplink device written into the running bridgedhcp
     *  config ("" when it was unresolved). Lets us reload when it appears. */
    private final Map<Integer, String> pdConfiguredUplink = new ConcurrentHashMap<>();
    /** Host IPv4 set the installed DNAT rules currently reflect; kept in sync. */
    private final java.util.Set<String> appliedHostIps = new java.util.LinkedHashSet<>();
    /** Re-scopes DNAT rules when the phone's host IPv4 set changes. */
    private Runnable hostIpListener = null;

    /** Records one installed forward so detach removes exactly what was added. */
    private static final class InstalledForward {
        final String guestIp, proto, hairpin;
        final int hs, he, gs, ge;
        InstalledForward(String guestIp, String proto, int hs, int he, int gs, int ge, String hairpin) {
            this.guestIp = guestIp; this.proto = proto; this.hairpin = hairpin;
            this.hs = hs; this.he = he; this.gs = gs; this.ge = ge;
        }
    }

    public LinuxBridgeBackend(@NonNull NetworkInstance inst) {
        super(inst);
        this.net = inst.getStore().backend;
        this.firewall = inst.getStore().firewall;
        this.br = inst.item.optString("bridge_name", "");
    }

    @Override
    public void start() throws Exception {
        if (net.isInterfaceExists(br)) {
            Log.w(TAG, fmt("Interface %s already exists, deleting", br));
            net.deleteBridge(br);
        }
        if (!net.createBridge(br))
            throw new RuntimeException(fmt("Failed to create bridge %s", br));
        var mac = resolveBridgeMac();
        if (mac != null && !net.setMacAddress(br, mac))
            Log.w(TAG, fmt("Failed to set MAC %s on %s", mac, br));
        net.setStp(br, inst.isStp());
        switch (inst.getUplinkMode()) {
            case L2:
                startL2();
                break;
            case L3:
                startL3();
                break;
        }
        if (!net.setLinkState(br, true))
            throw new RuntimeException(fmt("Failed to bring up bridge %s", br));
        if (inst.getUplinkMode() == UplinkMode.L3)
            startL3Services();
    }

    /**
     * The bridge needs a stable MAC: the configured one for L3/none, an
     * auto-generated persisted one otherwise. Without it the bridge MAC
     * would follow whichever port is enslaved.
     */
    @Nullable
    private String resolveBridgeMac() {
        var mac = inst.getBridgeMacAddress();
        if (mac != null) return mac;
        var generated = inst.item.optString("generated_mac", "");
        if (generated == null || generated.isEmpty()) {
            generated = NetUtils.generateRandomMac();
            inst.item.set("generated_mac", generated);
        }
        return generated;
    }

    private void startL2() throws Exception {
        var configured = inst.getL2Uplink();
        if (configured == null)
            throw new RuntimeException("L2 network has no uplink configured");
        var uplink = UplinkResolver.resolve(configured);
        if (uplink == null)
            throw new RuntimeException(fmt("Uplink \"%s\" not found", configured));
        resolvedUplink = uplink;
        // honour the configured toggle, but force pseudo-bridging when the
        // resolved device can't be enslaved (Wi-Fi STA / non-ethernet)
        boolean pseudo = inst.isL2PseudoBridge()
            || UplinkResolver.assumeDontBridge(uplink);
        if (pseudo) {
            pbridge = new Pbridge(uplink, br);
            if (!pbridge.start())
                throw new RuntimeException(fmt(
                    "Failed to start pseudo-bridge on %s", uplink));
        } else {
            uplinkEnslaved = net.addInterface(br, uplink);
            if (!uplinkEnslaved) throw new RuntimeException(fmt(
                "Uplink %s cannot be bridged (IFF_DONT_BRIDGE?); enable pseudo-bridge",
                uplink
            ));
        }
    }

    private void startL3() {
        // Stock GKI has no bridge VLAN filtering: each tagged VLAN gets its
        // own bridge ({br}v{hex}) plus an 802.1q trunk leg ({br}.{hex}) on
        // the main bridge. Access ports attach to the per-VLAN bridge,
        // trunk ports to the main bridge; L3 lives on the per-VLAN bridge.
        // VLAN 0 is the untagged domain: its L3 lives directly on the main
        // bridge (no per-VLAN bridge, no VID-0 leg -- that would only catch
        // priority-tagged frames, not untagged traffic).
        //
        // These bridges and legs face the VMs, so harden them as a router's
        // LAN side: disable IPv6 RA acceptance so a VM's RAs can't
        // autoconfigure an address or default route on the host (the kernel
        // default accept_ra=1 would otherwise allow it).
        net.disableAcceptRa(br); // main bridge (VLAN 0 / trunk backbone)
        for (var vlan : inst.getVlans()) {
            int vid = vlan.getVlanId();
            var dev = LinuxNetwork.vlanDevice(br, vid);
            if (vid != 0) {
                if (!net.createVlanBridge(br, vid))
                    Log.w(TAG, fmt("Failed to create per-VLAN bridge for vid %d", vid));
                createdVlanBridges.add(vid);
                net.disableAcceptRa(LinuxNetwork.vlanTrunkLeg(br, vid)); // vlan if
                net.disableAcceptRa(dev);                                // per-VLAN bridge
            }
            var net4 = vlan.getIpv4Network();
            if (net4 != null && !net.addAddress(dev, net4))
                Log.w(TAG, fmt("Failed to add IPv4 %s to %s", net4, dev));
            for (var cidr : vlan.getIpv4Secondary())
                addAddressSafe(dev, cidr, false);
            var net6 = vlan.getIpv6Network();
            if (net6 != null && !net.addAddress(dev, net6))
                Log.w(TAG, fmt("Failed to add IPv6 %s to %s", net6, dev));
            for (var cidr : vlan.getIpv6Secondary())
                addAddressSafe(dev, cidr, true);
        }
    }

    private void addAddressSafe(@NonNull String dev, @NonNull String cidr, boolean v6) {
        try {
            if (v6) net.addAddress(dev, IPv6Network.parse(cidr));
            else net.addAddress(dev, IPv4Network.parse(cidr));
        } catch (Exception e) {
            Log.w(TAG, fmt("Skipping invalid CIDR %s on %s", cidr, dev), e);
        }
    }

    private void startL3Services() {
        firewall.initNetwork(inst);
        net.populateRuleRoute(inst);
        var watcher = inst.getStore().context.getRouterWatcher();
        if (watcher != null) {
            watcher.setForNewNetwork(inst);
            // seed the host-IP set DNAT rules are scoped to, then track changes
            synchronized (this) {
                appliedHostIps.clear();
                appliedHostIps.addAll(watcher.getHostIpv4Addresses());
            }
            hostIpListener = this::onHostIpsChanged;
            watcher.addHostIpListener(hostIpListener);
        }
        if (BridgeDhcp.isNeeded(inst.getVlans())) {
            dhcp = new BridgeDhcp(inst, this::onPdAddressChanged);
            if (!dhcp.start())
                Log.e(TAG, fmt("Failed to start bridgedhcp for %s", br));
            snapshotPdUplinks();
        }
    }

    /** Records the uplink each DHCP-PD VLAN resolved to in the running config. */
    private synchronized void snapshotPdUplinks() {
        pdConfiguredUplink.clear();
        for (var vlan : inst.getVlans()) {
            if (vlan.getIpv6Source() != Ipv6Source.DHCP_PD) continue;
            var id = vlan.getPdUplink();
            if (id == null) continue;
            var dev = UplinkResolver.resolve(id);
            pdConfiguredUplink.put(vlan.getVlanId(), dev == null ? "" : dev);
        }
    }

    /**
     * Reloads bridgedhcp when a DHCP-PD VLAN's uplink now resolves to a
     * different device than the running config has -- most importantly when it
     * was unresolved at start (so PD was skipped) and the interface (e.g.
     * Wi-Fi) has since come up. A still-unresolved uplink is left alone: a
     * running PD client already retries on its own. Invoked on host-network
     * changes, so the freshly-connected uplink gets a PD client.
     */
    private synchronized void reconcilePdUplinks() {
        if (dhcp == null) return;
        boolean changed = false;
        for (var vlan : inst.getVlans()) {
            if (vlan.getIpv6Source() != Ipv6Source.DHCP_PD) continue;
            var id = vlan.getPdUplink();
            if (id == null) continue;
            var dev = UplinkResolver.resolve(id);
            if (dev == null) continue; // still down: bridgedhcp retries itself
            if (!dev.equals(pdConfiguredUplink.get(vlan.getVlanId()))) {
                changed = true;
                break;
            }
        }
        if (!changed) return;
        Log.i(TAG, fmt("PD uplink now available for %s; reloading bridgedhcp", br));
        dhcp.restart();
        snapshotPdUplinks();
    }

    /**
     * PD event from bridgedhcp: the helper already plumbed the address on
     * the per-VLAN bridge (and DHCPv6/RA follow it automatically); what is
     * left is the host side -- firewall accept rules for the delegated
     * subnet and the policy-routing entries for return traffic.
     */
    private synchronized void onPdAddressChanged(int vlanId, @Nullable IPv6Network address) {
        var dev = LinuxNetwork.vlanDevice(br, vlanId);
        var old = liveV6.remove(vlanId);
        if (old != null)
            firewall.removeLiveV6Subnet(inst, dev, old);
        if (address != null) {
            liveV6.put(vlanId, address);
            firewall.addLiveV6Subnet(inst, dev, address);
            net.populateRouteForNetwork(dev, address);
        }
    }

    @Override
    public void stop() {
        if (hostIpListener != null) {
            var watcher = inst.getStore().context.getRouterWatcher();
            if (watcher != null)
                watcher.removeHostIpListener(hostIpListener);
            hostIpListener = null;
        }
        synchronized (this) {
            appliedHostIps.clear();
        }
        liveV6.clear();
        if (dhcp != null) {
            dhcp.stop();
            dhcp = null;
        }
        if (pbridge != null) {
            pbridge.stop();
            pbridge = null;
        }
        if (uplinkEnslaved && resolvedUplink != null) {
            net.removeInterface(resolvedUplink);
            uplinkEnslaved = false;
        }
        if (inst.getUplinkMode() == UplinkMode.L3) {
            firewall.deinitNetwork(inst);
            for (var vid : createdVlanBridges)
                net.deleteVlanBridge(br, vid);
            createdVlanBridges.clear();
        }
        net.setLinkState(br, false);
        if (net.isInterfaceExists(br) && !net.deleteBridge(br))
            Log.w(TAG, fmt("Failed to delete bridge %s", br));
        macSecurityActive.clear();
        installedForwards.clear();
        forwardFailures.clear();
    }

    /**
     * Restarts pbridge / bridgedhcp if they died. The kernel bridge is the data
     * path, so these are auxiliary: pbridge (Wi-Fi uplink offload) is stateless,
     * and bridgedhcp recovers its leases/PD from its state file. The tap ports
     * stay enslaved to the bridge throughout, so nothing here touches them.
     */
    @Override
    public void reconcile() {
        var p = pbridge;
        if (p != null) p.reconcile();
        var d = dhcp;
        if (d != null) d.reconcile();
    }

    @Override
    public void attachNic(@NonNull VMNicConfig nic, @NonNull String tapName) throws Exception {
        if (net.isInterfaceExists(tapName)) net.deleteTap(tapName);
        if (!net.createTap(tapName))
            throw new RuntimeException(fmt("Failed to create TAP %s", tapName));
        try {
            // an access port may reference a VLAN the network didn't configure;
            // create its per-VLAN bridge on demand so the VM can still boot
            if (inst.getUplinkMode() == UplinkMode.L3 && nic.getVlanId() != null)
                ensureVlanBridge(nic.getVlanId());
            var target = resolveAttachBridge(nic);
            if (!net.addInterface(target, tapName))
                throw new RuntimeException(fmt(
                    "Failed to add TAP %s to bridge %s", tapName, target));
            // The guest MAC is passed to the hypervisor only. Setting it on
            // the host-side tap would duplicate the guest's MAC on the host
            // and break L2 forwarding.
            if (nic.isIsolated() && !net.setPortIsolated(tapName, true))
                Log.w(TAG, fmt("Failed to isolate port %s", tapName));
            applyMacSecurity(nic, tapName);
            if (!net.setLinkState(tapName, true))
                Log.w(TAG, fmt("Failed to bring up TAP %s", tapName));
            if (inst.getUplinkMode() == UplinkMode.L3) {
                if (dhcp != null) dhcp.reloadStaticLeases();
                installNicForwards(nic, tapName);
            }
        } catch (Exception e) {
            net.deleteTap(tapName);
            throw e;
        }
    }

    private void applyMacSecurity(@NonNull VMNicConfig nic, @NonNull String tapName) {
        if (!nic.isMacSecurity()) return;
        var mac = nic.getMacAddress();
        if (mac == null) {
            Log.w(TAG, fmt("MAC security on %s skipped: no MAC configured", tapName));
            macSecurityActive.put(tapName, false);
            return;
        }
        // "locked" bridge ports need kernel >= 5.16; degrade with a warning
        if (net.setPortLocked(tapName, true) && net.fdbAddStatic(mac, tapName)) {
            macSecurityActive.put(tapName, true);
        } else {
            Log.w(TAG, fmt(
                "MAC security unavailable for %s (kernel without locked bridge ports?)",
                tapName
            ));
            net.setPortLocked(tapName, false);
            macSecurityActive.put(tapName, false);
        }
    }

    /**
     * Ensures the per-VLAN bridge + trunk leg for an access port's VLAN exist,
     * creating them on demand for a VLAN the network config didn't declare
     * (an L2-only segment, no gateway/DHCP). Recorded so stop() removes it.
     * No-op for the trunk marker (4095) and for 0: a VID-0 leg must never
     * be created (it would catch only priority-tagged frames).
     */
    private synchronized void ensureVlanBridge(int vid) {
        if (vid == 0 || vid == 4095) return;
        var perBr = LinuxNetwork.perVlanBridge(br, vid);
        if (net.isInterfaceExists(perBr)) {
            createdVlanBridges.add(vid);
            return;
        }
        if (!net.createVlanBridge(br, vid))
            Log.w(TAG, fmt("Failed to create on-demand per-VLAN bridge for vid %d", vid));
        createdVlanBridges.add(vid);
    }

    /**
     * The bridge a NIC's tap attaches to: a trunk port (no VLAN id / the
     * 4095 marker) or any non-L3 network joins the main bridge; a tagged
     * access port joins its VLAN's per-VLAN bridge. VLAN 0 (untagged-only)
     * ports don't exist on a Linux bridge -- validation rejects them, since
     * without VLAN filtering they could only behave as trunks.
     */
    @NonNull
    private String resolveAttachBridge(@NonNull VMNicConfig nic) {
        if (inst.getUplinkMode() != UplinkMode.L3) return br;
        var vlanId = nic.getVlanId();
        if (vlanId == null || vlanId == 4095) return br;
        return LinuxNetwork.perVlanBridge(br, vlanId);
    }

    private synchronized void installNicForwards(@NonNull VMNicConfig nic, @NonNull String tapName) {
        var vlan = nic.resolveDhcpVlan(inst);
        if (vlan == null) return;
        if (!nic.isDhcp4LeaseEnabled() || !vlan.isDhcp4Enabled() || !vlan.isIpv4Snat())
            return;
        var net4 = vlan.getIpv4Network();
        if (net4 == null) return;
        String guestIp;
        try {
            guestIp = net4.addressAtOffset(nic.getDhcp4Offset()).toString();
        } catch (Exception e) {
            Log.w(TAG, "Invalid DHCPv4 lease offset, skipping forwards", e);
            return;
        }
        // NAT-loopback: a guest in this subnet reaching the forward gets
        // masqueraded so the target's reply returns via the gateway
        var hairpinSubnet = net4.toNetworkString();
        var installed = new java.util.ArrayList<InstalledForward>();
        var failures = new java.util.ArrayList<String>();
        for (var fwd : nic.getDhcp4Forwards()) {
            try {
                fwd.validate();
                // "any" installs one rule per concrete protocol (tcp+udp)
                for (var proto : fwd.protocols()) {
                    // host-IP-independent half (FORWARD ACCEPT + hairpin) once...
                    boolean ok = firewall.applyForwardBase(
                        br, guestIp, proto, fwd.guestStart(), fwd.guestEnd(), hairpinSubnet);
                    // ...then a DNAT per current host IP (scoped with -d)
                    for (var hostIp : appliedHostIps)
                        firewall.applyDnat(br, guestIp, proto, hostIp,
                            fwd.hostStart(), fwd.hostEnd(), fwd.guestStart(), fwd.guestEnd());
                    if (ok) installed.add(new InstalledForward(guestIp, proto,
                        fwd.hostStart(), fwd.hostEnd(), fwd.guestStart(), fwd.guestEnd(),
                        hairpinSubnet));
                    else failures.add(fmt("%s %s:%s", proto, fwd.host, fwd.guest));
                }
            } catch (Exception e) {
                failures.add(fmt("%s:%s (%s)", fwd.host, fwd.guest, e.getMessage()));
            }
        }
        if (!installed.isEmpty()) installedForwards.put(tapName, installed);
        if (!failures.isEmpty()) {
            forwardFailures.put(tapName, failures);
            Log.w(TAG, fmt("Forward install failures on %s: %s", tapName, failures));
        }
    }

    /** Removes exactly the forwards this tap installed (DNAT per host IP, then base). */
    private synchronized void removeNicForwards(@NonNull String tapName) {
        forwardFailures.remove(tapName);
        var installed = installedForwards.remove(tapName);
        if (installed == null) return;
        for (var f : installed) {
            for (var hostIp : appliedHostIps)
                firewall.removeDnat(br, f.guestIp, f.proto, hostIp,
                    f.hs, f.he, f.gs, f.ge);
            firewall.removeForwardBase(br, f.guestIp, f.proto, f.gs, f.ge, f.hairpin);
        }
    }

    /**
     * Re-scopes DNAT rules when the phone's host IPv4 set changes: adds rules
     * for newly-seen addresses, removes them for vanished ones, leaving the
     * FORWARD/hairpin rules (host-IP-independent) untouched.
     */
    private synchronized void onHostIpsChanged() {
        // a host-network change (e.g. Wi-Fi connecting) is also the cue to
        // pick up a PD uplink that was unavailable when the network started
        reconcilePdUplinks();
        var watcher = inst.getStore().context.getRouterWatcher();
        if (watcher == null) return;
        var target = watcher.getHostIpv4Addresses();
        var added = new java.util.ArrayList<String>();
        var removed = new java.util.ArrayList<String>();
        for (var ip : target)
            if (!appliedHostIps.contains(ip)) added.add(ip);
        for (var ip : appliedHostIps)
            if (!target.contains(ip)) removed.add(ip);
        if (added.isEmpty() && removed.isEmpty()) return;
        for (var entry : installedForwards.values())
            for (var f : entry) {
                for (var ip : added)
                    firewall.applyDnat(br, f.guestIp, f.proto, ip,
                        f.hs, f.he, f.gs, f.ge);
                for (var ip : removed)
                    firewall.removeDnat(br, f.guestIp, f.proto, ip,
                        f.hs, f.he, f.gs, f.ge);
            }
        appliedHostIps.clear();
        appliedHostIps.addAll(target);
    }

    @Override
    public void detachNic(@NonNull VMNicConfig nic, @NonNull String tapName) {
        if (inst.getUplinkMode() == UplinkMode.L3)
            removeNicForwards(tapName);
        net.removeInterface(tapName);
        net.deleteTap(tapName);
        macSecurityActive.remove(tapName);
        if (dhcp != null) dhcp.reloadStaticLeases();
    }

    @Override
    public JSONArray listAddresses() {
        var arr = net.listAddresses(br);
        for (var vlan : inst.getVlans()) {
            if (vlan.getVlanId() == 0) continue; // untagged = the main bridge
            var sub = net.listAddresses(
                LinuxNetwork.perVlanBridge(br, vlan.getVlanId()));
            for (int i = 0; i < sub.length(); i++)
                arr.put(sub.opt(i));
        }
        return arr;
    }

    /**
     * One row per address with three dimensions -- VLAN, bound/unbound,
     * configured/auto -- over the union, in every mode, of every address the
     * network carries: the live addresses on each bridge device (the main
     * bridge = VLAN 0, every per-VLAN bridge = its VID) and every configured
     * CIDR. A configured address that is also live appears once (configured +
     * bound); a configured one not yet on a device is configured + unbound; a
     * live one with no config is auto (link-local, RA, the L2 uplink mirror
     * pbridge parks on the main bridge, ...). The main bridge is always
     * scanned -- including L2/none mode, where it is the only device and carries
     * that mirror; per-VLAN bridges include on-demand ones, not just configured.
     */
    @NonNull
    @Override
    public JSONArray listAddressEntries() {
        var out = new JSONArray();
        // VID -> configured (VMM-managed) CIDRs; VLAN 0 included if declared,
        // empty in L2/none mode (getVlans() is L3-only).
        var configured = new java.util.LinkedHashMap<Integer, java.util.List<String>>();
        for (var vlan : inst.getVlans())
            configured.put(vlan.getVlanId(), configuredCidrs(vlan));
        // Every VID whose L3 device may carry addresses, in a stable order:
        // VLAN 0 (the main bridge, always -- the L2 mirror lands here), the
        // configured VLANs in config order, then any on-demand per-VLAN bridge.
        var vids = new java.util.LinkedHashSet<Integer>();
        vids.add(0);
        vids.addAll(configured.keySet());
        vids.addAll(createdVlanBridges);
        for (var vid : vids) {
            var dev = LinuxNetwork.vlanDevice(br, vid);
            // canonical key -> raw "ip/plen" of every live address on the device
            var live = new java.util.LinkedHashMap<String, String>();
            var liveArr = net.listAddresses(dev);
            for (int i = 0; i < liveArr.length(); i++) {
                var raw = liveArr.optString(i, "");
                if (!raw.isEmpty()) live.put(canonAddr(raw), raw);
            }
            for (var cidr : configured.getOrDefault(vid, java.util.List.of())) {
                boolean bound = live.remove(canonAddr(cidr)) != null;
                out.put(addressEntry(cidr, vid, bound, "configured"));
            }
            // The DHCP-PD host address is owned by the dedicated PD entry (it
            // carries the release/renew actions and the Searching state), so
            // drop it from the live set instead of re-listing it as "auto".
            var pdAddr = liveV6.get(vid);
            if (pdAddr != null) live.remove(canonAddr(pdAddr.toString()));
            for (var raw : live.values())
                out.put(addressEntry(raw, vid, true, "auto"));
        }
        // L2 pseudo-bridge: pbridge parks each learned guest address on the
        // uplink tagged with the offload magic (so the Wi-Fi firmware answers
        // ARP/NS for them). Those tagged addresses are exactly the pseudo-
        // bridged guests' IPs -- surface them under their own source; the metric
        // filter drops the host's own (untagged) uplink addresses.
        if (pbridge != null && resolvedUplink != null) {
            var guests = net.listAddresses(resolvedUplink, Constants.PBRIDGE_OFFLOAD_MAGIC);
            for (int i = 0; i < guests.length(); i++) {
                var raw = guests.optString(i, "");
                if (!raw.isEmpty())
                    out.put(addressEntry(raw, 0, true, "pbridge_guest"));
            }
        }
        return out;
    }

    /** Configured (VMM-managed) CIDRs of one VLAN: primary + secondary v4/v6. */
    @NonNull
    private static java.util.List<String> configuredCidrs(@NonNull VlanConfig vlan) {
        var list = new java.util.ArrayList<String>();
        var v4 = vlan.getIpv4Cidr();
        if (v4 != null) list.add(v4);
        list.addAll(vlan.getIpv4Secondary());
        if (vlan.getIpv6Source() == Ipv6Source.STATIC) {
            var v6 = vlan.getIpv6Cidr();
            if (v6 != null) list.add(v6);
        }
        list.addAll(vlan.getIpv6Secondary());
        return list;
    }

    /** Canonical "address/prefix" key so config and live forms compare equal. */
    @NonNull
    private static String canonAddr(@NonNull String cidr) {
        try {
            var n = IPNetwork.parse(cidr);
            return fmt("%s/%d", n.address().toString(), n.prefix());
        } catch (Exception e) {
            return cidr;
        }
    }

    @NonNull
    @Override
    public JSONArray listPdStatus() {
        // bridgedhcp's runtime PD state, keyed by VLAN id
        var runtime = new java.util.HashMap<Integer, JSONObject>();
        if (dhcp != null) {
            var rt = dhcp.getPdStatus();
            for (int i = 0; i < rt.length(); i++) {
                var o = rt.optJSONObject(i);
                if (o != null) runtime.put(o.optInt("vlan", -1), o);
            }
        }
        // One row per VLAN configured for DHCP-PD, so the UI always reflects
        // the intent: when bridgedhcp has no client for it (the PD uplink was
        // unresolved at start), synthesize a "no_uplink" row rather than
        // hiding the VLAN entirely.
        var out = new JSONArray();
        for (var vlan : inst.getVlans()) {
            if (vlan.getIpv6Source() != Ipv6Source.DHCP_PD) continue;
            int vid = vlan.getVlanId();
            var rt = runtime.get(vid);
            if (rt != null) {
                out.put(rt);
                continue;
            }
            // No runtime client: tell apart a genuinely unresolved uplink from
            // a helper that just isn't serving this VLAN yet (starting/down).
            var id = vlan.getPdUplink();
            var resolvable = id != null && UplinkResolver.resolve(id) != null;
            out.put(pdEntry(vid, resolvable ? "searching" : "no_uplink", ""));
        }
        return out;
    }

    @NonNull
    private static JSONObject pdEntry(int vlan, @NonNull String state, @NonNull String prefix) {
        var o = new JSONObject();
        try {
            o.put("vlan", vlan);
            o.put("state", state);
            o.put("prefix", prefix);
        } catch (JSONException ignored) {
        }
        return o;
    }

    @Override
    public boolean renewPd(int vlanId) {
        return dhcp != null && dhcp.renewPd(vlanId);
    }

    @Override
    public boolean releasePd(int vlanId) {
        return dhcp != null && dhcp.releasePd(vlanId);
    }

    @NonNull
    @Override
    public JSONArray listTools() {
        var out = new JSONArray();
        if (pbridge != null) out.put(toolEntry("pbridge", pbridge.isRunning()));
        if (dhcp != null) out.put(toolEntry("bridgedhcp", dhcp.isRunning()));
        return out;
    }

    @Nullable
    @Override
    public java.util.List<String> toolLog(@NonNull String key) {
        if ("pbridge".equals(key) && pbridge != null) return pbridge.getLog();
        if ("bridgedhcp".equals(key) && dhcp != null) return dhcp.getLog();
        return null;
    }

    @Override
    public JSONArray listInterfaces(VMInstanceStore vms) {
        return net.listInterfaces(vms, br);
    }

    @Override
    public JSONArray listNeighbors() {
        var arr = net.listNeighbors(br);
        for (var vlan : inst.getVlans()) {
            if (vlan.getVlanId() == 0) continue; // untagged = the main bridge
            var sub = net.listNeighbors(
                LinuxNetwork.perVlanBridge(br, vlan.getVlanId()));
            for (int i = 0; i < sub.length(); i++)
                arr.put(sub.opt(i));
        }
        return arr;
    }

    @Override
    public JSONArray listDhcpLeases() {
        if (dhcp == null) return new JSONArray();
        return dhcp.getLeases();
    }

    @Override
    public void appendInfo(@NonNull JSONObject obj) throws JSONException {
        if (dhcp != null) {
            var status = dhcp.getStatus();
            if (status != null) {
                var pdStatus = extractPdStatus(status);
                if (pdStatus.length() > 0) obj.put("pd_status", pdStatus);
            }
        }
        if (resolvedUplink != null) obj.put("resolved_uplink", resolvedUplink);
        if (!macSecurityActive.isEmpty()) {
            var sec = new JSONObject();
            for (var entry : macSecurityActive.entrySet())
                sec.put(entry.getKey(), entry.getValue());
            obj.put("mac_security_active", sec);
        }
        appendForwardFailures(obj, forwardFailures);
    }

    /**
     * Converts bridgedhcp's status document into the legacy pd_status
     * array shape ({vlan_id, state, prefix}).
     */
    @NonNull
    private static JSONArray extractPdStatus(@NonNull JSONObject status) {
        var out = new JSONArray();
        var ifaces = status.optJSONArray("ifaces");
        if (ifaces == null) return out;
        for (int i = 0; i < ifaces.length(); i++) {
            var iface = ifaces.optJSONObject(i);
            if (iface == null) continue;
            var state = iface.optString("pd_state", "");
            if (state.isEmpty()) continue;
            try {
                var entry = new JSONObject();
                entry.put("vlan_id", Integer.parseInt(iface.optString("tag", "-1")));
                entry.put("state", state);
                var prefix = iface.optString("pd_prefix", "");
                if (!prefix.isEmpty()) entry.put("prefix", prefix);
                out.put(entry);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    @NonNull
    @Override
    public Map<Integer, IPv6Network> getLiveV6Networks() {
        return Map.copyOf(liveV6);
    }
}
