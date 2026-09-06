// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashSet;

import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.NicLeaseOffsets;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/**
 * Fills in any unassigned DHCPv4 static-lease offset on a VM right before it
 * starts. A migrated config (or any lease enabled without an offset) carries an
 * empty offset; this allocates the smallest free value from
 * {@link NicLeaseOffsets#FIRST} up, skipping the VLAN's dynamic pool and any
 * offset already used by another VM -- or this VM's own other NICs -- on the
 * same network/VLAN, then persists the result so the guest IP stays stable
 * across restarts.
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

                var used = new HashSet<Long>();
                vmStore.forEach((id, vm) -> {
                    // its persisted copy is stale vs the config in hand
                    if (id.equals(selfId)) return;
                    NicLeaseOffsets.addOffsets(used, vm, network, vlan, NicLeaseOffsets.Family.IPV4);
                });
                NicLeaseOffsets.addOffsets(used, config, network, vlan, NicLeaseOffsets.Family.IPV4);
                long offset = NicLeaseOffsets.resolve(
                    NicLeaseOffsets.FIRST, used, vlan, NicLeaseOffsets.Family.IPV4);
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
}
