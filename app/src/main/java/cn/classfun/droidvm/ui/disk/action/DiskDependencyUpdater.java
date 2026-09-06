// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.disk.tree.CursorPlan;

/**
 * Writes the persisted half of a {@link CursorPlan} - the slots of other VMs, and the saved
 * slots of the VM being edited - into the VM store. The editor applies its own in-memory rows
 * separately, when its branch panel closes.
 */
public final class DiskDependencyUpdater {
    private static final String TAG = "DiskDependencyUpdater";

    private DiskDependencyUpdater() {
    }

    /**
     * Returns only after the updated VMStore is durably saved; failures are fail-closed so the
     * caller never deletes a file a slot still points at.
     */
    public static boolean applyPlan(@NonNull Context context, @NonNull CursorPlan plan) {
        var changes = plan.persistedChanges();
        if (changes.isEmpty()) return true;
        var vmStore = new VMStore();
        if (!vmStore.load(vmStore, context)) {
            Log.e(TAG, "Failed to load VM store before updating disk dependencies");
            return false;
        }
        var byVm = new HashMap<UUID, List<CursorPlan.Change>>();
        for (var c : changes) {
            if (c.from.vmId == null) continue;
            byVm.computeIfAbsent(c.from.vmId, k -> new ArrayList<>()).add(c);
        }
        boolean changed = false;
        for (var e : byVm.entrySet()) {
            var vm = vmStore.findById(e.getKey());
            if (vm == null) continue;
            var disks = vm.item.opt("disks", null);
            if (disks == null || !disks.is(DataItem.Type.ARRAY)) continue;
            changed |= applyToDisks(disks, e.getValue());
        }
        if (!changed) return true;
        if (vmStore.save(context)) return true;
        Log.e(TAG, "Failed to save redirected VM disk dependencies");
        return false;
    }

    /**
     * Pure slot rewrite for one VM, kept separate for deterministic unit coverage. Highest slot
     * first so a removal never shifts a slot still to be visited; a slot whose path no longer
     * matches what the plan was computed from is left alone (something else changed it since).
     * Read-only is only ever added, never taken away.
     */
    static boolean applyToDisks(@NonNull DataItem disks, @NonNull List<CursorPlan.Change> changes) {
        var ordered = new ArrayList<>(changes);
        ordered.sort((a, b) -> Integer.compare(b.from.slot, a.from.slot));
        boolean changed = false;
        for (var change : ordered) {
            int slot = change.from.slot;
            if (slot < 0 || slot >= disks.size()) continue;
            var disk = disks.get(slot);
            var path = disk.optString("path", "");
            if (change.from.path == null || !change.from.path.equals(path)) {
                Log.w(TAG, fmt("Slot %d of %s moved since planning (%s); skipped",
                    slot, change.from.vmName, path));
                continue;
            }
            if (change.cleared()) {
                disks.remove(slot);
            } else {
                disk.set("path", change.to.path);
                if (change.to.readonly) disk.set("readonly", true);
            }
            changed = true;
        }
        return changed;
    }
}
