package cn.classfun.droidvm.ui.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/**
 * Fills in any unassigned DHCPv4 static-lease offset on a VM right before it
 * starts. A migrated config (or any lease enabled without an offset) carries an
 * empty offset; this allocates the smallest free value from 64 up, skipping the
 * VLAN's dynamic pool and any offset already used by another VM -- or this VM's
 * own other NICs -- on the same network/VLAN, then persists the result so the
 * guest IP stays stable across restarts.
 * <p>
 * Allocation is app-side and persisted here; nothing else assigns offsets.
 * </p>
 */
public final class NicLeaseAllocator {
    private static final String TAG = "NicLeaseAllocator";

    private NicLeaseAllocator() {
    }

    /**
     * Resolves and persists empty DHCPv4 offsets for {@code config}. Best
     * effort: failures are logged, never thrown, so a start is not blocked.
     */
    public static void resolveAndPersist(@NonNull VMConfig config, @NonNull Context ctx) {
        try {
            var vmStore = new VMStore();
            vmStore.load(ctx);
            var netStore = new NetworkStore();
            netStore.load(ctx);

            var selfId = config.getId();
            boolean[] changed = {false};
            config.forEachNic(nic -> {
                if (!nic.isDhcp4LeaseEnabled() || nic.hasDhcp4Offset()) return;
                var netId = nic.getNetworkId();
                if (netId == null) return;
                var network = netStore.findById(netId);
                if (network == null) return;
                var vlan = nic.resolveDhcpVlan(network);
                if (vlan == null || !vlan.isDhcp4Enabled()) return;
                var net4 = vlan.getIpv4Network();
                if (net4 == null) return;

                long offset = firstFree(usedOffsets(vmStore, config, selfId, network, vlan),
                    vlan, net4);
                if (offset < 0) {
                    Log.w(TAG, fmt("No free DHCPv4 offset for a NIC on network %s", netId));
                    return;
                }
                nic.setDhcp4Offset(offset);
                changed[0] = true;
            });

            if (changed[0] && vmStore.findById(selfId) != null) {
                vmStore.update(config);
                vmStore.save(ctx);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to allocate DHCPv4 lease offsets", e);
        }
    }

    /**
     * Offsets already taken on {@code network}/{@code vlan} (IPv4): every other
     * VM's NICs plus this VM's own already-assigned NICs, so a second NIC
     * resolved in the same pass sees the first one's freshly set offset.
     */
    @NonNull
    private static Set<Long> usedOffsets(
        @NonNull VMStore vmStore, @NonNull VMConfig config, @NonNull UUID selfId,
        @NonNull NetworkConfig network, @NonNull VlanConfig vlan
    ) {
        var used = new HashSet<Long>();
        vmStore.forEach((id, vm) -> {
            if (id.equals(selfId)) return; // its persisted copy is stale vs config
            addOffsets(used, vm, network, vlan);
        });
        addOffsets(used, config, network, vlan);
        return used;
    }

    private static void addOffsets(
        @NonNull Set<Long> used, @NonNull VMConfig vm,
        @NonNull NetworkConfig network, @NonNull VlanConfig vlan
    ) {
        var netIdStr = network.getId().toString();
        vm.forEachNic(nic -> {
            if (!netIdStr.equals(nic.getNetworkId())) return;
            if (!nic.isDhcp4LeaseEnabled() || !nic.hasDhcp4Offset()) return;
            var nv = nic.resolveDhcpVlan(network);
            if (nv == null || nv.getVlanId() != vlan.getVlanId()) return;
            used.add(nic.getDhcp4Offset());
        });
    }

    /** Smallest offset >= 64 outside the dynamic pool and not already used. */
    private static long firstFree(
        @NonNull Set<Long> used, @NonNull VlanConfig vlan, @NonNull IPv4Network net4
    ) {
        long poolStart = vlan.getDhcp4OffsetStart();
        long poolEnd = vlan.getDhcp4OffsetEnd();
        long maxOffset = net4.totalAddresses() - 2; // addressAtOffset valid 1...total-2
        for (long c = 64; c <= maxOffset; c++) {
            if (c >= poolStart && c <= poolEnd) continue; // skip the dynamic pool
            if (used.contains(c)) continue;
            return c;
        }
        return -1;
    }
}
