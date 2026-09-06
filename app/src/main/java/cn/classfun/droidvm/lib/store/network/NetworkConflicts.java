// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.base.DataStore;

/**
 * What two networks may not share, and -- the point of this class -- which two networks that
 * question is even asked about.
 *
 * <p>An address conflict is a conflict only where both networks are actually seen by the same
 * stack. Two Linux bridges route in the host kernel, so their prefixes must not overlap; two
 * gVisor networks collide the same way inside their own user-space stacks. A Linux bridge and a
 * gVisor network never see each other's routes at all -- gVisor's addressing lives entirely in
 * its own process, the kernel has no idea the prefix exists -- so the same subnet on both is
 * fine, and refusing it only costs the user address space for no reason. An L2 network has no
 * prefix to conflict with in the first place; what it cannot share is the physical uplink it
 * bridges onto, which one network at a time owns.
 *
 * <p>Names are the exception and are deliberately not scoped here: the display name and the
 * bridge interface name stay unique app-wide across every kind, because they name a thing the
 * user picks from one list and the host resolves in one namespace. Those are the store's
 * {@code isNameUnique} / {@code isBridgeNameUnique}.
 */
public final class NetworkConflicts {
    private NetworkConflicts() {
    }

    /** What collided. */
    public enum Kind {
        IPV4,
        IPV6,
        UPLINK,
    }

    /** One collision: what of ours hit what of theirs, and whose. */
    public static final class Conflict {
        @NonNull
        public final Kind kind;
        /** Our subnet / uplink, as text. */
        @NonNull
        public final String mine;
        /** Theirs, as text. */
        @NonNull
        public final String theirs;
        /** The network we collided with. */
        @NonNull
        public final NetworkConfig other;

        Conflict(
            @NonNull Kind kind,
            @NonNull String mine,
            @NonNull String theirs,
            @NonNull NetworkConfig other
        ) {
            this.kind = kind;
            this.mine = mine;
            this.theirs = theirs;
            this.other = other;
        }

        /** The other network's display name, never null for a message. */
        @NonNull
        public String otherName() {
            var name = other.getName();
            return name == null ? "" : name;
        }
    }

    /**
     * Whether a conflict between these two is even possible: same bridge type, and -- since an
     * L2 network conflicts on its uplink and an L3 one on its prefixes -- same uplink mode. This
     * is also exactly the set a packaged network may be imported into, so that every setting
     * that is specific to a kind (L3 DHCP pool offsets, gVisor's IPv6 SNAT) carries over intact.
     */
    public static boolean sameKind(@NonNull NetworkConfig a, @NonNull NetworkConfig b) {
        return a.getBridgeType() == b.getBridgeType()
            && a.getUplinkMode() == b.getUplinkMode();
    }

    /** The first conflict between {@code cfg} and anything in the store, or null if it is free. */
    @Nullable
    public static Conflict find(
        @NonNull NetworkConfig cfg,
        @NonNull DataStore<? extends NetworkConfig> store,
        @Nullable UUID exclude
    ) {
        return find(cfg, snapshot(store), exclude);
    }

    /** The same, against an explicit list. */
    @Nullable
    public static Conflict find(
        @NonNull NetworkConfig cfg,
        @NonNull List<? extends NetworkConfig> others,
        @Nullable UUID exclude
    ) {
        var mine4 = new ArrayList<IPv4Network>();
        var mine6 = new ArrayList<IPv6Network>();
        collectSubnets(cfg.getVlans(), mine4, mine6);
        var myUplink = cfg.getUplinkMode() == UplinkMode.L2 ? cfg.getL2Uplink() : null;
        for (var other : others) {
            if (exclude != null && exclude.toString().equals(other.item.optString("id", "")))
                continue;
            if (!sameKind(cfg, other)) continue;
            if (myUplink != null) {
                var theirs = other.getL2Uplink();
                if (theirs != null && theirs.trim().equalsIgnoreCase(myUplink.trim()))
                    return new Conflict(Kind.UPLINK, myUplink, theirs, other);
                continue;
            }
            var conflict = findAddressConflict(mine4, mine6, other);
            if (conflict != null) return conflict;
        }
        return null;
    }

    @Nullable
    private static Conflict findAddressConflict(
        @NonNull List<IPv4Network> mine4,
        @NonNull List<IPv6Network> mine6,
        @NonNull NetworkConfig other
    ) {
        var their4 = new ArrayList<IPv4Network>();
        var their6 = new ArrayList<IPv6Network>();
        collectSubnets(other.getVlans(), their4, their6);
        for (var mine : mine4)
            for (var theirs : their4)
                if (mine.overlaps(theirs)) return new Conflict(
                    Kind.IPV4, mine.toString(), theirs.toString(), other);
        for (var mine : mine6)
            for (var theirs : their6)
                if (mine.overlaps(theirs)) return new Conflict(
                    Kind.IPV6, mine.toString(), theirs.toString(), other);
        return null;
    }

    /**
     * The first pair of this config's own subnets that overlap each other, as {@code {a, b}}, or
     * null when it is self-consistent. Not scoped by anything: one network's VLANs share a stack
     * by definition.
     */
    @Nullable
    public static String[] findSelfOverlap(@NonNull NetworkConfig cfg) {
        var mine4 = new ArrayList<IPv4Network>();
        var mine6 = new ArrayList<IPv6Network>();
        collectSubnets(cfg.getVlans(), mine4, mine6);
        for (int i = 0; i < mine4.size(); i++)
            for (int j = i + 1; j < mine4.size(); j++)
                if (mine4.get(i).overlaps(mine4.get(j))) return new String[]{
                    mine4.get(i).toString(), mine4.get(j).toString()};
        for (int i = 0; i < mine6.size(); i++)
            for (int j = i + 1; j < mine6.size(); j++)
                if (mine6.get(i).overlaps(mine6.get(j))) return new String[]{
                    mine6.get(i).toString(), mine6.get(j).toString()};
        return null;
    }

    /** Appends every subnet these VLANs hold, primary and secondary, to the given lists. */
    public static void collectSubnets(
        @NonNull Iterable<VlanConfig> vlans,
        @NonNull List<IPv4Network> out4,
        @NonNull List<IPv6Network> out6
    ) {
        for (var vlan : vlans) {
            var net4 = vlan.getIpv4Network();
            if (net4 != null) out4.add(net4);
            for (var cidr : vlan.getIpv4Secondary()) {
                try {
                    out4.add(IPv4Network.parse(cidr));
                } catch (Exception ignored) {
                }
            }
            var net6 = vlan.getIpv6Network();
            if (net6 != null) out6.add(net6);
            for (var cidr : vlan.getIpv6Secondary()) {
                try {
                    out6.add(IPv6Network.parse(cidr));
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Every config in a store, as a plain list. */
    @NonNull
    public static List<NetworkConfig> snapshot(@NonNull DataStore<? extends NetworkConfig> store) {
        var out = new ArrayList<NetworkConfig>();
        for (int i = 0; i < store.size(); i++) out.add(store.get(i));
        return out;
    }
}
