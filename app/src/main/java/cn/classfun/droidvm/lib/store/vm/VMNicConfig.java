package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
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
     * VLAN, or the untagged domain when trunked.
     */
    @Nullable
    public VlanConfig resolveDhcpVlan(@NonNull NetworkConfig network) {
        if (network.getUplinkMode() != UplinkMode.L3) return null;
        var vlanId = getVlanId();
        return network.findVlan(vlanId == null ? 0 : vlanId);
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
            if (vlanId < 0 || vlanId > 4094)
                throw new IllegalArgumentException(fmt("Invalid NIC VLAN id: %d", vlanId));
            if (network.getUplinkMode() == UplinkMode.L3 && network.findVlan(vlanId) == null)
                throw new IllegalArgumentException(fmt(
                    "VLAN %d is not configured on network %s", vlanId, network.getName()
                ));
        }
        var vlan = resolveDhcpVlan(network);
        if (isDhcp4LeaseEnabled()) {
            if (vlan == null || !vlan.isDhcp4Enabled())
                throw new IllegalArgumentException(
                    "DHCPv4 static lease requires an L3 network with DHCPv4 enabled"
                );
            if (mac == null)
                throw new IllegalArgumentException("DHCPv4 static lease requires a MAC address");
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
            var net6 = vlan.getIpv6Network();
            if (net6 != null) net6.addressAtOffset(getDhcp6Offset());
            for (var fwd : getDhcp6Forwards()) {
                fwd.validate();
                if (!vlan.isIpv6Snat()) throw new IllegalArgumentException(
                    "IPv6 port forwards require IPv6 SNAT on the network"
                );
            }
        }
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

        public void validate() {
            if (!proto.equals("tcp") && !proto.equals("udp"))
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
