// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.io.IOException;

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
 * GUI allocation uses the guarded VM inventory. Native package imports also allocate leases.
 * </p>
 */
public final class NicLeaseAllocator {
    private NicLeaseAllocator() {
    }

    /**
     * Worker-only. Failures stop the start; the caller's config is updated only after save.
     */
    public static void resolveAndPersist(@NonNull VMConfig config, @NonNull Context ctx) throws Exception {
        boolean[] needed = {false};
        config.forEachNic(nic -> {
            if (nic.isDhcp4LeaseEnabled() && !nic.hasDhcp4Offset()) needed[0] = true;
        });
        if (!needed[0]) return;
        VMStore.updatePersistedField(ctx, config, "networks", (changed, observed) -> {
            var netStore = new NetworkStore();
            if (!netStore.load(ctx)) throw new IOException("Cannot read network configuration");
            resolve(changed, observed, netStore);
        });
    }

    static void resolve(VMConfig config, VMStore vmStore, NetworkStore netStore) {
        var selfId = config.getId();
        config.forEachNic(nic -> {
            if (!nic.isDhcp4LeaseEnabled() || nic.hasDhcp4Offset()) return;
            var netId = nic.getNetworkId();
            if (netId == null) throw new IllegalStateException("Static lease has no network");
            var network = netStore.findById(netId);
            if (network == null) throw new IllegalStateException("Static lease network no longer exists");
            var vlan = nic.resolveDhcpVlan(network);
            if (vlan == null || !vlan.isDhcp4Enabled())
                throw new IllegalStateException("Static lease requires a DHCPv4 network");

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
                throw new IllegalStateException("No free DHCPv4 lease offset on network " + netId);
            }
            nic.setDhcp4Offset(offset);
        });
    }
}
