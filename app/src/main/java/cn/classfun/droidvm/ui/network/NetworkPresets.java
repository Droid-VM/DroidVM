// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.network;

import static cn.classfun.droidvm.lib.utils.NetUtils.generateRandomMac;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import cn.classfun.droidvm.daemon.network.backend.UplinkResolver;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.Ipv6Source;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkConfigValidator;
import cn.classfun.droidvm.lib.store.network.NetworkConflicts;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.store.network.VlanConfig;

/**
 * Ready-made network configs, and the address picking they share with the network editor.
 *
 * <p>The editor builds its blank form from the same helpers, so a network created here in one
 * tap and one typed out by hand pick their subnets from the same pool and avoid the same
 * conflicts. Everything is static: none of it needs a live screen.
 */
public final class NetworkPresets {
    /** bridge + "v"/"." + 2-char VLAN code must fit IFNAMSIZ (15 usable). */
    public static final int MAX_BRIDGE_NAME_LEN = NetworkConfigValidator.MAX_BRIDGE_NAME_LEN;
    private static final Random RANDOM = new Random();

    private NetworkPresets() {
    }

    /**
     * A Wi-Fi pseudo-bridge: the VM sits on the phone's own Wi-Fi segment and takes its address
     * from the upstream router, so there is nothing for us to address, NAT or serve DHCP on.
     * Wi-Fi in station mode cannot be enslaved into a Linux bridge, hence pseudo-bridging.
     *
     * @param name used as both the network name and the bridge interface name
     */
    @NonNull
    public static NetworkConfig wifiPseudoBridge(@NonNull String name) {
        var config = newConfig(name, BridgeType.LINUX, UplinkMode.L2);
        config.l2().set("uplink", UplinkResolver.ID_WIFI);
        config.l2().set("pseudo_bridge", true);
        return config;
    }

    /**
     * A routed network with one untagged VLAN: NAT to whatever uplink the host has, and DHCP for
     * the VMs on it. Which address families that covers depends on the bridge type -- see
     * {@link #newVlan}.
     *
     * @param name used as both the network name and the bridge interface name
     * @param pair primary IPv4 and IPv6 CIDRs from {@link #pickFreeCidrPair}, or null to leave
     *             the VLAN unaddressed
     */
    @NonNull
    public static NetworkConfig routedNat(
        @NonNull BridgeType type, @NonNull String name, @Nullable String[] pair
    ) {
        var config = newConfig(name, type, UplinkMode.L3);
        config.l3().set("mac_address", generateRandomMac());
        var vlans = DataItem.newArray();
        vlans.append(newVlan(0, type, pair).item);
        config.l3().set("vlans", vlans);
        return config;
    }

    /**
     * The shell both presets fill in. {@code auto_up} is on because a preset exists to spare the
     * user the setup: the daemon reads networks.json on start and brings it up from there, which
     * is also the only way a network created before the daemon runs ever starts.
     */
    @NonNull
    private static NetworkConfig newConfig(
        @NonNull String name, @NonNull BridgeType type, @NonNull UplinkMode mode
    ) {
        var config = new NetworkConfig();
        config.setName(name);
        config.setBridgeName(name);
        config.item.set("auto_up", true);
        config.item.set("stp", false);
        config.setBridgeType(type);
        config.setUplinkMode(mode);
        return config;
    }

    /** A new VLAN entry with the given paired networks applied (unaddressed when null). */
    @NonNull
    public static VlanConfig newVlan(
        int vlanId, @NonNull BridgeType type, @Nullable String[] pair
    ) {
        var vlan = VlanConfig.createDefault(vlanId);
        if (pair != null) {
            vlan.ipv4().set("cidr", pair[0]);
            vlan.ipv6().set("cidr", pair[1]);
        }
        var ipv6 = vlan.ipv6();
        if (type == BridgeType.GVISOR) {
            // gVisor has IPv6 SNAT, so the ULA prefix is routable: default on
            ipv6.set("snat", true);
        } else {
            // a Linux bridge has no IPv6 NAT and Android rarely holds a
            // routed prefix, so serving the ULA via DHCPv6/SLAAC hands VMs
            // addresses with no connectivity: default to a static ULA CIDR
            // with serving off, and pre-fill the Wi-Fi PD uplink for when the
            // user switches the source to DHCP-PD
            ipv6.set("snat", false);
            ipv6.set("source", Ipv6Source.STATIC.key());
            var pd = DataItem.newObject();
            pd.set("uplink", UplinkResolver.ID_WIFI);
            ipv6.set("pd", pd);
            ipv6.get("dhcp").set("enabled", false);
            ipv6.get("slaac").set("enabled", false);
        }
        return vlan;
    }

    /**
     * Picks N in 50-250 so that 192.168.N.1/24 and fd00:N::1/64 are both free of overlaps with
     * everything in {@code used4}/{@code used6}. Returns null when no N fits.
     */
    @Nullable
    public static String[] pickFreeCidrPair(
        @NonNull List<IPv4Network> used4, @NonNull List<IPv6Network> used6
    ) {
        for (int attempt = 0; attempt < 400; attempt++) {
            int n = 50 + RANDOM.nextInt(201); // 50-250
            IPv4Network cand4;
            IPv6Network cand6;
            try {
                cand4 = IPv4Network.parse(fmt("192.168.%d.1/24", n));
                cand6 = IPv6Network.parse(fmt("fd00:%d::1/64", n));
            } catch (Exception e) {
                continue;
            }
            boolean conflicts = false;
            for (var ex : used4)
                if (cand4.overlaps(ex)) {
                    conflicts = true;
                    break;
                }
            if (!conflicts) for (var ex : used6)
                if (cand6.overlaps(ex)) {
                    conflicts = true;
                    break;
                }
            if (!conflicts) return new String[]{cand4.toString(), cand6.toString()};
        }
        return null;
    }

    /** Every subnet in the store bar {@code exclude}, so a suggestion can avoid them all. */
    public static void collectStoreNetworks(
        @NonNull NetworkStore store, @Nullable UUID exclude,
        @NonNull List<IPv4Network> out4, @NonNull List<IPv6Network> out6
    ) {
        collectStoreNetworks(store, exclude, null, out4, out6);
    }

    /**
     * The same, narrowed to one bridge type. Only networks of the same type can actually collide
     * (see {@link NetworkConflicts}), so a suggestion for a gVisor network has no reason to walk
     * around what the Linux bridges hold -- and every such detour costs it a subnet from a pool
     * of 201.
     *
     * @param type the type being addressed, or null to avoid every network whatever its type
     */
    public static void collectStoreNetworks(
        @NonNull NetworkStore store, @Nullable UUID exclude, @Nullable BridgeType type,
        @NonNull List<IPv4Network> out4, @NonNull List<IPv6Network> out6
    ) {
        store.forEach((id, cfg) -> {
            if (exclude != null && exclude.equals(id)) return;
            if (type != null && cfg.getBridgeType() != type) return;
            NetworkConflicts.collectSubnets(cfg.getVlans(), out4, out6);
        });
    }

    /**
     * {@code base}, or the first free variant of it, as a name no other network uses for either
     * its display name or its bridge. Falls back to {@code base} when nothing fits, leaving the
     * duplicate for the caller's validation to reject.
     */
    @NonNull
    public static String uniqueName(@NonNull NetworkStore store, @NonNull String base) {
        if (isNameFree(store, base)) return base;
        var prefix = base.replaceAll("\\d+$", "");
        var digits = base.substring(prefix.length());
        long n;
        try {
            n = digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (NumberFormatException e) {
            n = 0;
        }
        for (int i = 0; i < 1000; i++) {
            var candidate = fmt("%s%d", prefix, ++n);
            if (candidate.length() <= MAX_BRIDGE_NAME_LEN && isNameFree(store, candidate))
                return candidate;
        }
        return base;
    }

    private static boolean isNameFree(@NonNull NetworkStore store, @NonNull String name) {
        return store.isNameUnique(name) && store.isBridgeNameUnique(name, null);
    }
}
