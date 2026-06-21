package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.utils.NetUtils;

/**
 * Wrapper over one entry of a VM config's "networks" array (one NIC).
 */
public final class VMNicConfig {
    public final DataItem item;

    public VMNicConfig(@NonNull DataItem item) {
        this.item = item;
    }

    @Nullable
    public String getNetworkId() {
        var id = item.optString("network_id", null);
        return id == null || id.isEmpty() ? null : id;
    }

    @Nullable
    public String getTapName() {
        var tap = item.optString("tap_name", null);
        return tap == null || tap.isEmpty() ? null : tap;
    }

    public void setTapName(@NonNull String tapName) {
        item.set("tap_name", tapName);
    }

    @Nullable
    public String getMacAddress() {
        var mac = item.optString("mac_address", null);
        return mac == null || mac.isEmpty() ? null : mac;
    }

    public boolean isMacSecurity() {
        return item.optBoolean("mac_security", false);
    }

    public boolean isIsolated() {
        return item.optBoolean("isolated", false);
    }

    /** Access VLAN id; null means trunk (all VLANs). */
    @Nullable
    public Integer getVlanId() {
        var v = item.opt("vlan_id", null);
        if (v == null || !v.is(DataItem.Type.INTEGER)) return null;
        return (int) v.asInteger();
    }

    @NonNull
    private DataItem lease(@NonNull String key) {
        var s = item.opt(key, null);
        if (s == null || !s.is(DataItem.Type.OBJECT)) {
            // set() stores a copy; re-read so callers mutate the stored item
            item.set(key, DataItem.newObject());
            s = item.get(key);
        }
        return s;
    }

    public boolean isDhcp4LeaseEnabled() {
        return lease("dhcp4_lease").optBoolean("enabled", false);
    }

    public long getDhcp4Offset() {
        return lease("dhcp4_lease").optLong("offset", 64);
    }

    public boolean isDhcp6LeaseEnabled() {
        return lease("dhcp6_lease").optBoolean("enabled", false);
    }

    public long getDhcp6Offset() {
        return lease("dhcp6_lease").optLong("offset", 64);
    }

    /**
     * Whether this NIC has a concrete DHCPv4 lease offset stored. A migrated
     * config (or any lease enabled without an offset) leaves it empty; the
     * offset is allocated at start time. Reads without creating the lease
     * object so it stays a pure query.
     */
    public boolean hasDhcp4Offset() {
        return hasOffset("dhcp4_lease");
    }

    public boolean hasDhcp6Offset() {
        return hasOffset("dhcp6_lease");
    }

    private boolean hasOffset(@NonNull String leaseKey) {
        var l = item.opt(leaseKey, null);
        if (l == null || !l.is(DataItem.Type.OBJECT)) return false;
        var off = l.opt("offset", null);
        return off != null && off.is(DataItem.Type.INTEGER) && off.asInteger() > 0;
    }

    public void setDhcp4Offset(long offset) {
        lease("dhcp4_lease").set("offset", offset);
    }

    @NonNull
    public List<PortForward> getDhcp4Forwards() {
        return forwards("dhcp4_lease");
    }

    @NonNull
    public List<PortForward> getDhcp6Forwards() {
        return forwards("dhcp6_lease");
    }

    @NonNull
    private List<PortForward> forwards(@NonNull String key) {
        var out = new ArrayList<PortForward>();
        var arr = lease(key).opt("forwards", null);
        if (arr == null || !arr.is(DataItem.Type.ARRAY)) return out;
        for (var e : arr.asArray()) {
            if (!e.is(DataItem.Type.OBJECT)) continue;
            out.add(new PortForward(
                e.optString("proto", "tcp"),
                e.optString("host", ""),
                e.optString("guest", "")
            ));
        }
        return out;
    }

    /**
     * The VLAN of this NIC the DHCP services apply to: the pinned access
     * VLAN, or the untagged domain (VLAN 0) when trunked -- either the
     * implicit trunk (no vlan_id) or the explicit 4095 trunk marker.
     */
    @Nullable
    public VlanConfig resolveDhcpVlan(@NonNull NetworkConfig network) {
        if (network.getUplinkMode() != UplinkMode.L3) return null;
        var vlanId = getVlanId();
        if (vlanId == null || vlanId == 4095) vlanId = 0;
        return network.findVlan(vlanId);
    }

    /**
     * Validates this NIC against its network config. Throws
     * {@link IllegalArgumentException} on the first violation.
     */
    public void validate(@NonNull NetworkConfig network) {
        var mac = getMacAddress();
        if (mac != null && !NetUtils.isValidMac(mac))
            throw new IllegalArgumentException(fmt("Invalid NIC MAC address: %s", mac));
        if (isMacSecurity() && mac == null)
            throw new IllegalArgumentException("MAC security requires a configured MAC address");
        var vlanId = getVlanId();
        if (vlanId != null) {
            // Port semantics: 0 = untagged-only, 4095 = trunk marker
            // (equivalent to no vlan_id), 1..4094 = tagged access.
            if (vlanId < 0 || vlanId > 4095)
                throw new IllegalArgumentException(fmt("Invalid NIC VLAN id: %d", vlanId));
            // An untagged-only port needs per-port VLAN filtering, which a
            // Linux bridge on stock GKI cannot do -- attaching to the main
            // bridge would actually be a trunk (flooded tagged frames
            // included). Refuse instead of silently degrading; gvisor's
            // switchcore can enforce untagged-only, so 0 stays valid there.
            if (vlanId == 0 && network.getBridgeType() == BridgeType.LINUX)
                throw new IllegalArgumentException(
                    "Untagged-only (VLAN 0) ports are not supported on a Linux "
                        + "bridge; use trunk or a tagged VLAN");
            // An access VLAN not present in the network config is allowed: it
            // is created on demand as an L2-only segment when the NIC attaches.
        }
        var vlan = resolveDhcpVlan(network);
        if (isDhcp4LeaseEnabled()) {
            if (vlan == null || !vlan.isDhcp4Enabled())
                throw new IllegalArgumentException(
                    "DHCPv4 static lease requires an L3 network with DHCPv4 enabled"
                );
            if (mac == null)
                throw new IllegalArgumentException("DHCPv4 static lease requires a MAC address");
            // an unassigned offset means there is no IP to boot with. Every
            // path but daemon autoUp assigns one first (the GUI forces it, app
            // start runs the allocator), so an empty offset here is a migrated
            // VM autoUp has never resolved -- refuse rather than guess.
            if (!hasDhcp4Offset())
                throw new IllegalArgumentException(
                    "DHCPv4 static lease has no assigned offset");
            var net4 = vlan.getIpv4Network();
            if (net4 != null) net4.addressAtOffset(getDhcp4Offset());
            for (var fwd : getDhcp4Forwards()) {
                fwd.validate();
                if (!vlan.isIpv4Snat()) throw new IllegalArgumentException(
                    "IPv4 port forwards require IPv4 SNAT on the network"
                );
            }
        }
        if (isDhcp6LeaseEnabled()) {
            if (vlan == null || !vlan.isDhcp6Enabled())
                throw new IllegalArgumentException(
                    "DHCPv6 static lease requires an L3 network with DHCPv6 enabled"
                );
            if (!hasDhcp6Offset())
                throw new IllegalArgumentException(
                    "DHCPv6 static lease has no assigned offset");
            var net6 = vlan.getIpv6Network();
            if (net6 != null) net6.addressAtOffset(getDhcp6Offset());
            for (var fwd : getDhcp6Forwards()) {
                fwd.validate();
                // snat may be stored true on a Linux bridge but only takes
                // effect on gVisor, so require the bridge type as well
                if (!vlan.isIpv6Snat()
                    || network.getBridgeType() != BridgeType.GVISOR)
                    throw new IllegalArgumentException(
                        "IPv6 port forwards require IPv6 SNAT on a gVisor network"
                    );
            }
        }
    }

    /**
     * An in-memory copy of this NIC with every option the target network can't
     * honour stripped out; the saved config is left untouched. A NIC keeps its
     * L3-only settings (DHCP static lease, port forwards) in the stored config
     * so they survive being moved back to an L3 network -- the GUI just hides
     * them elsewhere. On an L2 / non-DHCP network those settings are inert, so
     * for this run we drop them rather than refuse to start. Genuine
     * misconfigurations are left in place for {@link #validate} to reject.
     *
     * @param dropped collects a human-readable note for each option removed
     */
    @NonNull
    public VMNicConfig sanitizedFor(@NonNull NetworkConfig network, @NonNull List<String> dropped) {
        DataItem copy;
        try {
            copy = DataItem.from(item.toJson()); // deep, independent copy
        } catch (JSONException e) {
            throw new IllegalStateException("NIC config is not serializable", e);
        }
        var sane = new VMNicConfig(copy);
        var vlan = sane.resolveDhcpVlan(network);
        // A DHCP static lease (and any forwards riding on it) only means
        // something on an L3 network that actually serves that DHCP family.
        if (sane.isDhcp4LeaseEnabled() && (vlan == null || !vlan.isDhcp4Enabled())) {
            copy.remove("dhcp4_lease");
            dropped.add("DHCPv4 static lease");
        } else if (sane.isDhcp4LeaseEnabled() && !sane.getDhcp4Forwards().isEmpty()
            && !vlan.isIpv4Snat()) {
            dropForwards(copy, "dhcp4_lease");
            dropped.add("IPv4 port forwards (network has no IPv4 SNAT)");
        }
        if (sane.isDhcp6LeaseEnabled() && (vlan == null || !vlan.isDhcp6Enabled())) {
            copy.remove("dhcp6_lease");
            dropped.add("DHCPv6 static lease");
        } else if (sane.isDhcp6LeaseEnabled() && !sane.getDhcp6Forwards().isEmpty()
            && (!vlan.isIpv6Snat() || network.getBridgeType() != BridgeType.GVISOR)) {
            dropForwards(copy, "dhcp6_lease");
            dropped.add("IPv6 port forwards (require a gVisor network)");
        }
        return sane;
    }

    /** Drops the forwards array from a lease object, keeping the lease itself. */
    private static void dropForwards(@NonNull DataItem nic, @NonNull String leaseKey) {
        var lease = nic.opt(leaseKey, null);
        if (lease != null && lease.is(DataItem.Type.OBJECT)) lease.remove("forwards");
    }

    /** One "proto host:guest" port forward entry; ports may be N or N-M ranges. */
    public static final class PortForward {
        public final String proto;
        public final String host;
        public final String guest;

        public PortForward(@NonNull String proto, @NonNull String host, @NonNull String guest) {
            this.proto = proto;
            this.host = host;
            this.guest = guest;
        }

        public int hostStart() {
            return rangeStart(host);
        }

        public int hostEnd() {
            return rangeEnd(host);
        }

        public int guestStart() {
            return rangeStart(guest);
        }

        public int guestEnd() {
            return rangeEnd(guest);
        }

        /** Concrete protocols this entry installs: "any" expands to tcp+udp. */
        @NonNull
        public List<String> protocols() {
            if (proto.equals("any")) return List.of("tcp", "udp");
            return List.of(proto);
        }

        public void validate() {
            if (!proto.equals("tcp") && !proto.equals("udp") && !proto.equals("any"))
                throw new IllegalArgumentException(fmt("Invalid forward protocol: %s", proto));
            int hs = parsePort(host, true), he = parsePort(host, false);
            int gs = parsePort(guest, true), ge = parsePort(guest, false);
            if (he < hs || ge < gs)
                throw new IllegalArgumentException(fmt(
                    "Invalid port range in forward %s -> %s", host, guest
                ));
            if (he - hs != ge - gs)
                throw new IllegalArgumentException(fmt(
                    "Forward port ranges differ in size: %s -> %s", host, guest
                ));
        }

        private static int rangeStart(@NonNull String spec) {
            return parsePort(spec, true);
        }

        private static int rangeEnd(@NonNull String spec) {
            return parsePort(spec, false);
        }

        private static int parsePort(@NonNull String spec, boolean start) {
            var s = spec.trim();
            int dash = s.indexOf('-');
            String part;
            if (dash < 0) part = s;
            else part = start ? s.substring(0, dash) : s.substring(dash + 1);
            int port;
            try {
                port = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(fmt("Invalid port: %s", spec));
            }
            if (port < 1 || port > 65535)
                throw new IllegalArgumentException(fmt("Port out of range: %s", spec));
            return port;
        }
    }
}
