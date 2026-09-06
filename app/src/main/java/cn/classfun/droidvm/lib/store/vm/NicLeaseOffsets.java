// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigInteger;
import java.util.Set;

import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.VlanConfig;

/**
 * Which DHCP static-lease offsets a VLAN has left, and which one a NIC should take.
 *
 * <p>An offset is what a static lease actually stores: the host part, counted from the VLAN's
 * network address, so the lease survives the network being re-addressed. Two NICs on the same
 * VLAN holding the same offset would be handed the same IP, so an offset is allocated against
 * everything already on that VLAN -- every other VM's NICs, and the VM's own other NICs.
 *
 * <p>Pure arithmetic over configs: no store, no context, no side effects. Callers decide where
 * the VMs come from and what to do with the answer.
 */
public final class NicLeaseOffsets {
    /** Static leases start here, leaving 1..63 for whatever the host wants at the low end. */
    public static final long FIRST = 64;
    /** Cap on how far a search walks before it gives up, so a huge VLAN cannot hang it. */
    private static final long MAX_PROBES = 1L << 16;

    private NicLeaseOffsets() {
    }

    /** Which address family's lease is meant. */
    public enum Family {
        IPV4,
        IPV6,
    }

    /**
     * The offsets one VM's NICs hold on this network/VLAN, appended to {@code used}. Callers walk
     * their own store: everything on the VLAN counts, including the resolving VM's other NICs, so
     * that two NICs resolved in one pass cannot land on the same offset.
     */
    public static void addOffsets(
        @NonNull Set<Long> used,
        @NonNull VMConfig vm,
        @NonNull NetworkConfig network,
        @NonNull VlanConfig vlan,
        @NonNull Family family
    ) {
        addOffsets(used, vm, network, vlan, family, null);
    }

    /**
     * The same, with one NIC left out -- the one being resolved, whose own offset is the thing
     * being asked about and so must not count as taken.
     */
    public static void addOffsets(
        @NonNull Set<Long> used,
        @NonNull VMConfig vm,
        @NonNull NetworkConfig network,
        @NonNull VlanConfig vlan,
        @NonNull Family family,
        @Nullable VMNicConfig exclude
    ) {
        var netIdStr = network.item.optString("id", "");
        if (netIdStr.isEmpty()) return;
        vm.forEachNic(nic -> {
            // same underlying entry, re-wrapped by forEachNic
            if (exclude != null && nic.item == exclude.item) return;
            if (!netIdStr.equals(nic.getNetworkId())) return;
            if (!hasOffset(nic, family)) return;
            var nicVlan = nic.resolveDhcpVlan(network);
            if (nicVlan == null || nicVlan.getVlanId() != vlan.getVlanId()) return;
            used.add(offsetOf(nic, family));
        });
    }

    /**
     * The offset a NIC should end up with: {@code wanted} when nothing is in its way, otherwise
     * the next free one above it, wrapping back to {@link #FIRST} when the top of the VLAN is
     * reached. Returns -1 when the VLAN has no free offset at all (or cannot host one), which is
     * the caller's cue to fall back to a dynamic address.
     *
     * <p>Searching upward from what was asked for, rather than from {@link #FIRST}, is what keeps
     * an imported VM's addresses recognisable: a package whose NICs sat at .70 and .71 lands on
     * .70 and .71 again unless something is already there, and only drifts by as much as it has
     * to.
     */
    public static long resolve(
        long wanted,
        @NonNull Set<Long> used,
        @NonNull VlanConfig vlan,
        @NonNull Family family
    ) {
        long max = maxOffset(vlan, family);
        if (max < FIRST) return -1;
        long poolStart = family == Family.IPV4
            ? vlan.getDhcp4OffsetStart() : vlan.getDhcp6OffsetStart();
        long poolEnd = family == Family.IPV4
            ? vlan.getDhcp4OffsetEnd() : vlan.getDhcp6OffsetEnd();
        long span = max - FIRST + 1;
        long probes = Math.min(span, MAX_PROBES);
        long start = wanted < FIRST || wanted > max ? FIRST : wanted;
        for (long i = 0; i < probes; i++) {
            // wrap rather than stop at the top: the fallback is a dynamic
            // address, so a free offset below the wanted one still beats it
            long c = FIRST + ((start - FIRST + i) % span);
            if (c >= poolStart && c <= poolEnd) continue; // the dynamic pool
            if (used.contains(c)) continue;
            return c;
        }
        return -1;
    }

    /** The highest offset this VLAN can address, or -1 when it has no network of that family. */
    private static long maxOffset(@NonNull VlanConfig vlan, @NonNull Family family) {
        if (family == Family.IPV4) {
            var net4 = vlan.getIpv4Network();
            // addressAtOffset is valid for 1..total-2
            return net4 == null ? -1 : net4.totalAddresses() - 2;
        }
        var net6 = vlan.getIpv6Network();
        // A delegated prefix has no CIDR here until it is handed one at run time; its host part
        // is a /64's worth either way, so the probe cap is the only bound that matters.
        if (net6 == null) return vlan.hasIpv6() ? Long.MAX_VALUE - 1 : -1;
        var total = net6.totalAddresses().subtract(BigInteger.valueOf(2));
        var cap = BigInteger.valueOf(Long.MAX_VALUE - 1);
        return total.compareTo(cap) >= 0 ? Long.MAX_VALUE - 1 : total.longValue();
    }

    private static boolean hasOffset(@NonNull VMNicConfig nic, @NonNull Family family) {
        if (family == Family.IPV4) return nic.isDhcp4LeaseEnabled() && nic.hasDhcp4Offset();
        return nic.isDhcp6LeaseEnabled() && nic.hasDhcp6Offset();
    }

    private static long offsetOf(@NonNull VMNicConfig nic, @NonNull Family family) {
        return family == Family.IPV4 ? nic.getDhcp4Offset() : nic.getDhcp6Offset();
    }
}
