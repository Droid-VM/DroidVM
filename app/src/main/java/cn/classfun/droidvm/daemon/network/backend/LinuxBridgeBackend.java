package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.network.backend.pd.Dhcp6PdClient;
import cn.classfun.droidvm.daemon.network.backend.pd.Duid;
import cn.classfun.droidvm.daemon.vm.VMInstanceStore;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Address;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.network.Ipv6Source;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.store.vm.VMNicConfig;
import cn.classfun.droidvm.lib.utils.NetUtils;

/**
 * Linux-bridge data path: kernel bridge (+ VLAN filtering and per-VLAN
 * 802.1q subinterfaces for L3 mode), dnsmasq for DHCP/RA, iptables for
 * SNAT and port forwards, pbridge for Wi-Fi L2 uplinks and DHCPv6-PD
 * clients for delegated prefixes.
 */
public final class LinuxBridgeBackend extends BridgeBackend {
    private static final String TAG = "LinuxBridgeBackend";
    private final LinuxNetwork net;
    private final FirewallHelper firewall;
    private final String br;
    private Dnsmasq dnsmasq = null;
    private Pbridge pbridge = null;
    private String resolvedUplink = null;
    private boolean uplinkEnslaved = false;
    private final Map<Integer, Dhcp6PdClient> pdClients = new HashMap<>();
    /** vlanId -> gateway-addressed /64 from a live PD delegation. */
    private final Map<Integer, IPv6Network> liveV6 = new ConcurrentHashMap<>();
    private final Map<String, Boolean> macSecurityActive = new ConcurrentHashMap<>();

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
            case NONE:
                break;
        }
        if (!net.setLinkState(br, true))
            throw new RuntimeException(fmt("Failed to bring up bridge %s", br));
        for (var vlan : inst.getVlans())
            if (!vlan.isUntagged())
                net.setLinkState(
                    LinuxNetwork.vlanSubInterface(br, vlan.getVlanId()), true);
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
        boolean pseudo;
        switch (inst.getL2PseudoBridge()) {
            case "on":
                pseudo = true;
                break;
            case "off":
                pseudo = false;
                break;
            default:
                pseudo = UplinkResolver.assumeDontBridge(uplink);
                break;
        }
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
        boolean vlanFiltering = inst.hasTaggedVlans();
        if (vlanFiltering) net.setVlanFiltering(br, true);
        for (var vlan : inst.getVlans()) {
            String dev;
            if (vlan.isUntagged()) {
                dev = br;
            } else {
                int vid = vlan.getVlanId();
                net.bridgeVlanAdd(br, vid, false, false, true);
                net.addVlanSubInterface(br, vid);
                dev = LinuxNetwork.vlanSubInterface(br, vid);
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
        if (watcher != null) watcher.setForNewNetwork(inst);
        startPdClients();
        if (Dnsmasq.isNeeded(inst.getVlans(), liveV6)) {
            dnsmasq = new Dnsmasq(inst);
            if (!dnsmasq.start(liveV6))
                Log.e(TAG, fmt("Failed to start dnsmasq for %s", br));
        }
    }

    private void startPdClients() {
        for (var vlan : inst.getVlans()) {
            if (vlan.getIpv6Source() != Ipv6Source.DHCP_PD)
                continue;
            var uplink = vlan.getPdUplink();
            if (uplink == null) continue;
            var duid = resolvePdDuid(vlan);
            var crc = new CRC32();
            crc.update(inst.getId().toString().getBytes());
            crc.update(vlan.getVlanId());
            var client = new Dhcp6PdClient(
                vlan.getVlanId(), uplink, duid, (int) crc.getValue(),
                this::onPdPrefixChanged
            );
            pdClients.put(vlan.getVlanId(), client);
            client.start();
        }
    }

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

    /** PD callback: plumb the delegated /64 into address/firewall/dnsmasq. */
    private synchronized void onPdPrefixChanged(int vlanId, @Nullable IPv6Network delegated) {
        var vlan = inst.findVlan(vlanId);
        if (vlan == null) return;
        var dev = vlan.isUntagged() ? br
            : LinuxNetwork.vlanSubInterface(br, vlanId);
        var old = liveV6.remove(vlanId);
        if (old != null) {
            net.removeAddress(dev, old);
            firewall.removeLiveV6Subnet(inst, dev, old);
        }
        if (delegated != null) {
            // use the first /64 of the delegation; gateway = base + 1
            var base = delegated.networkAddress();
            var gateway = new IPv6Network(
                new IPv6Address(base.value().add(BigInteger.ONE)), 64
            );
            liveV6.put(vlanId, gateway);
            if (!net.addAddress(dev, gateway))
                Log.w(TAG, fmt("Failed to add PD address %s to %s", gateway, dev));
            firewall.addLiveV6Subnet(inst, dev, gateway);
            net.populateRouteForNetwork(dev, gateway);
        }
        if (dnsmasq != null) {
            dnsmasq.restart(liveV6);
        } else if (Dnsmasq.isNeeded(inst.getVlans(), liveV6)) {
            dnsmasq = new Dnsmasq(inst);
            dnsmasq.start(liveV6);
        }
    }

    @Override
    public void stop() {
        for (var client : pdClients.values())
            client.stop();
        pdClients.clear();
        liveV6.clear();
        if (dnsmasq != null) {
            dnsmasq.stop();
            dnsmasq = null;
        }
        if (pbridge != null) {
            pbridge.stop();
            pbridge = null;
        }
        if (uplinkEnslaved && resolvedUplink != null) {
            net.removeInterface(resolvedUplink);
            uplinkEnslaved = false;
        }
        if (inst.getUplinkMode() == UplinkMode.L3)
            firewall.deinitNetwork(inst);
        net.setLinkState(br, false);
        if (net.isInterfaceExists(br) && !net.deleteBridge(br))
            Log.w(TAG, fmt("Failed to delete bridge %s", br));
        macSecurityActive.clear();
    }

    @Override
    public void attachNic(@NonNull VMNicConfig nic, @NonNull String tapName) throws Exception {
        if (net.isInterfaceExists(tapName)) net.deleteTap(tapName);
        if (!net.createTap(tapName))
            throw new RuntimeException(fmt("Failed to create TAP %s", tapName));
        try {
            if (!net.addInterface(br, tapName))
                throw new RuntimeException(fmt(
                    "Failed to add TAP %s to bridge %s", tapName, br));
            // The guest MAC is passed to the hypervisor only. Setting it on
            // the host-side tap would duplicate the guest's MAC on the host
            // and break L2 forwarding.
            if (nic.isIsolated() && !net.setPortIsolated(tapName, true))
                Log.w(TAG, fmt("Failed to isolate port %s", tapName));
            applyMacSecurity(nic, tapName);
            applyPortVlan(nic, tapName);
            if (!net.setLinkState(tapName, true))
                Log.w(TAG, fmt("Failed to bring up TAP %s", tapName));
            if (inst.getUplinkMode() == UplinkMode.L3) {
                if (dnsmasq != null) dnsmasq.reloadStaticLeases();
                applyNicForwards(nic, true);
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

    private void applyPortVlan(@NonNull VMNicConfig nic, @NonNull String tapName) {
        if (!inst.hasTaggedVlans()) return;
        var vlanId = nic.getVlanId();
        if (vlanId == null) {
            // trunk: carry every configured tagged VLAN, untagged stays PVID 1
            for (var vlan : inst.getVlans())
                if (!vlan.isUntagged())
                    net.bridgeVlanAdd(tapName, vlan.getVlanId(), false, false, false);
        } else if (vlanId != 0) {
            // access port: replace the default untagged domain membership
            net.bridgeVlanDel(tapName, 1, false);
            net.bridgeVlanAdd(tapName, vlanId, true, true, false);
        }
        // vlanId == 0: untagged domain, default PVID 1 membership is correct
    }

    private void applyNicForwards(@NonNull VMNicConfig nic, boolean apply) {
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
        for (var fwd : nic.getDhcp4Forwards()) {
            try {
                fwd.validate();
                if (apply) firewall.applyForwardRange(
                    br, guestIp, fwd.proto, null,
                    fwd.hostStart(), fwd.hostEnd(), fwd.guestStart(), fwd.guestEnd());
                else firewall.removeForwardRange(
                    br, guestIp, fwd.proto, null,
                    fwd.hostStart(), fwd.hostEnd(), fwd.guestStart(), fwd.guestEnd());
            } catch (Exception e) {
                Log.w(TAG, fmt("Skipping invalid forward %s -> %s", fwd.host, fwd.guest), e);
            }
        }
    }

    @Override
    public void detachNic(@NonNull VMNicConfig nic, @NonNull String tapName) {
        if (inst.getUplinkMode() == UplinkMode.L3)
            applyNicForwards(nic, false);
        net.removeInterface(tapName);
        net.deleteTap(tapName);
        macSecurityActive.remove(tapName);
        if (dnsmasq != null) dnsmasq.reloadStaticLeases();
    }

    @Override
    public JSONArray listAddresses() {
        var arr = net.listAddresses(br);
        for (var vlan : inst.getVlans()) {
            if (vlan.isUntagged()) continue;
            var sub = net.listAddresses(
                LinuxNetwork.vlanSubInterface(br, vlan.getVlanId()));
            for (int i = 0; i < sub.length(); i++)
                arr.put(sub.opt(i));
        }
        return arr;
    }

    @Override
    public JSONArray listInterfaces(VMInstanceStore vms) {
        return net.listInterfaces(vms, br);
    }

    @Override
    public JSONArray listNeighbors() {
        var arr = net.listNeighbors(br);
        for (var vlan : inst.getVlans()) {
            if (vlan.isUntagged()) continue;
            var sub = net.listNeighbors(
                LinuxNetwork.vlanSubInterface(br, vlan.getVlanId()));
            for (int i = 0; i < sub.length(); i++)
                arr.put(sub.opt(i));
        }
        return arr;
    }

    @Override
    public JSONArray listDhcpLeases() {
        return net.listDhcpLeases(br);
    }

    @Override
    public void appendInfo(@NonNull JSONObject obj) throws JSONException {
        obj.put("dnsmasq_running", dnsmasq != null && dnsmasq.isRunning());
        if (dnsmasq != null) obj.put("dnsmasq_exit_code", dnsmasq.getExitCode());
        if (pbridge != null) {
            obj.put("pbridge_running", pbridge.isRunning());
            obj.put("pbridge_exit_code", pbridge.getExitCode());
        }
        if (resolvedUplink != null) obj.put("resolved_uplink", resolvedUplink);
        if (!pdClients.isEmpty()) {
            var arr = new JSONArray();
            for (var client : pdClients.values())
                arr.put(client.toStatusJson());
            obj.put("pd_status", arr);
        }
        if (!macSecurityActive.isEmpty()) {
            var sec = new JSONObject();
            for (var entry : macSecurityActive.entrySet())
                sec.put(entry.getKey(), entry.getValue());
            obj.put("mac_security_active", sec);
        }
    }

    @NonNull
    @Override
    public Map<Integer, IPv6Network> getLiveV6Networks() {
        return Map.copyOf(liveV6);
    }
}
