package cn.classfun.droidvm.lib.store.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigInteger;
import java.util.HashSet;

import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.utils.NetUtils;

/**
 * Validates a schema-v2 network config. Shared by the edit UI and the
 * daemon's create/modify handlers. Throws {@link IllegalArgumentException}
 * with a human-readable message on the first violation found.
 */
public final class NetworkConfigValidator {
    private NetworkConfigValidator() {
    }

    public static void validate(@NonNull NetworkConfig config) {
        var name = config.getName();
        if (name == null || name.trim().isEmpty())
            throw new IllegalArgumentException("Network name must not be empty");
        var bridgeName = config.getBridgeName();
        if (bridgeName == null || !bridgeName.matches("[a-zA-Z][a-zA-Z0-9_-]*"))
            throw new IllegalArgumentException(fmt("Invalid bridge name: %s", bridgeName));
        // Cap at 12 so the longest derived name still fits IFNAMSIZ (15 usable):
        // a per-VLAN bridge / trunk leg appends "v" or "." plus a 2-char VLAN
        // code (bridge + 3). See LinuxNetwork.vlanCode / perVlanBridge.
        if (bridgeName.length() > 12)
            throw new IllegalArgumentException("Bridge name longer than 12 characters");

        var type = config.getBridgeType();
        var mode = config.getUplinkMode();
        switch (mode) {
            case L2:
                validateL2(config, type);
                break;
            case L3:
                validateL3(config, type);
                break;
        }
    }

    private static void validateL2(@NonNull NetworkConfig config, @NonNull BridgeType type) {
        if (type != BridgeType.LINUX)
            throw new IllegalArgumentException("L2 bridging requires a Linux bridge");
        var uplink = config.getL2Uplink();
        if (uplink == null || uplink.trim().isEmpty())
            throw new IllegalArgumentException("L2 bridging requires an uplink interface");
        // pseudo_bridge is a boolean toggle; nothing further to validate
    }

    private static void validateL3(@NonNull NetworkConfig config, @NonNull BridgeType type) {
        validateMac(config.getBridgeMacAddress());
        // zero VLANs is valid: a VM-internal network the host doesn't join
        var vlans = config.getVlans();
        var seen = new HashSet<Integer>();
        for (var vlan : vlans) {
            int id = vlan.getVlanId();
            if (id < 0 || id > 4094)
                throw new IllegalArgumentException(fmt("Invalid VLAN id: %d", id));
            if (!seen.add(id))
                throw new IllegalArgumentException(fmt("Duplicate VLAN id: %d", id));
            validateVlan(vlan, type);
        }
    }

    private static void validateVlan(@NonNull VlanConfig vlan, @NonNull BridgeType type) {
        var label = vlan.isUntagged() ? "untagged" : String.valueOf(vlan.getVlanId());

        var cidr4 = vlan.getIpv4Cidr();
        IPv4Network net4 = null;
        if (cidr4 != null) {
            try {
                net4 = IPv4Network.parse(cidr4);
            } catch (Exception e) {
                throw new IllegalArgumentException(fmt(
                    "VLAN %s: invalid IPv4 CIDR: %s", label, cidr4
                ));
            }
            if (net4.prefix() > 30) throw new IllegalArgumentException(fmt(
                "VLAN %s: IPv4 prefix /%d too small for a gateway network",
                label, net4.prefix()
            ));
            if (net4.address().equals(net4.networkAddress())
                || net4.address().equals(net4.broadcastAddress()))
                throw new IllegalArgumentException(fmt(
                    "VLAN %s: IPv4 gateway address must be a host address, not %s",
                    label, net4.address()
                ));
        }
        if (vlan.isDhcp4Enabled()) {
            if (net4 == null) throw new IllegalArgumentException(fmt(
                "VLAN %s: DHCPv4 requires a primary IPv4 network", label
            ));
            validateOffsets(label, "DHCPv4",
                vlan.getDhcp4OffsetStart(), vlan.getDhcp4OffsetEnd(),
                BigInteger.valueOf(net4.totalAddresses() - 1));
        }

        var source = vlan.getIpv6Source();
        if (source == Ipv6Source.DHCP_PD) {
            if (type != BridgeType.LINUX) throw new IllegalArgumentException(fmt(
                "VLAN %s: DHCP-PD is only available on Linux bridges", label
            ));
            var uplink = vlan.getPdUplink();
            if (uplink == null || uplink.trim().isEmpty())
                throw new IllegalArgumentException(fmt(
                    "VLAN %s: DHCP-PD requires an uplink interface", label
                ));
            var duid = vlan.getPdDuid();
            if (duid != null && !duid.isEmpty() && !duid.matches("([0-9a-fA-F]{2}:)*[0-9a-fA-F]{2}"))
                throw new IllegalArgumentException(fmt("VLAN %s: invalid DUID", label));
        } else {
            var cidr6 = vlan.getIpv6Cidr();
            if (cidr6 != null) {
                IPv6Network net6;
                try {
                    net6 = IPv6Network.parse(cidr6);
                } catch (Exception e) {
                    throw new IllegalArgumentException(fmt(
                        "VLAN %s: invalid IPv6 CIDR: %s", label, cidr6
                    ));
                }
                if (net6.prefix() > 126) throw new IllegalArgumentException(fmt(
                    "VLAN %s: IPv6 prefix /%d too small", label, net6.prefix()
                ));
                if (vlan.isSlaacEnabled() && net6.prefix() != 64)
                    throw new IllegalArgumentException(fmt(
                        "VLAN %s: SLAAC requires a /64 network", label
                    ));
            }
        }
        // ipv6.snat may be stored true on a Linux bridge: it has no effect
        // there (the Android kernel has no IPv6 NAT) but is kept so the
        // setting survives a round-trip through the Linux bridge type
        if (vlan.isDhcp6Enabled()) {
            if (!vlan.hasIpv6()) throw new IllegalArgumentException(fmt(
                "VLAN %s: DHCPv6 requires a primary IPv6 network", label
            ));
            validateOffsets(label, "DHCPv6",
                vlan.getDhcp6OffsetStart(), vlan.getDhcp6OffsetEnd(), null);
        }

        if (type == BridgeType.GVISOR
            && (!vlan.getIpv4Secondary().isEmpty() || !vlan.getIpv6Secondary().isEmpty()))
            throw new IllegalArgumentException(fmt(
                "VLAN %s: secondary networks are not supported on gVisor bridges", label
            ));
        for (var cidr : vlan.getIpv4Secondary())
            if (!IPv4Network.isValid(cidr)) throw new IllegalArgumentException(fmt(
                "VLAN %s: invalid secondary IPv4 CIDR: %s", label, cidr
            ));
        for (var cidr : vlan.getIpv6Secondary())
            if (!IPv6Network.isValid(cidr)) throw new IllegalArgumentException(fmt(
                "VLAN %s: invalid secondary IPv6 CIDR: %s", label, cidr
            ));
    }

    private static void validateOffsets(
        @NonNull String label, @NonNull String what,
        long start, long end, @Nullable BigInteger maxExclusive
    ) {
        if (start <= 0 || end < start) throw new IllegalArgumentException(fmt(
            "VLAN %s: invalid %s pool offsets %d-%d", label, what, start, end
        ));
        if (maxExclusive != null
            && BigInteger.valueOf(end).compareTo(maxExclusive) >= 0)
            throw new IllegalArgumentException(fmt(
                "VLAN %s: %s pool offset %d exceeds the network size", label, what, end
            ));
    }

    private static void validateMac(@Nullable String mac) {
        if (mac == null) return;
        if (!NetUtils.isValidMac(mac))
            throw new IllegalArgumentException(fmt("Invalid MAC address: %s", mac));
    }
}
