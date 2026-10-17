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
import java.util.HashSet;
import java.io.IOException;
import org.json.JSONArray;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.base.VmConfigOwnership;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
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

    /** Worker-only planning read; a failed read must never mean "no VM references". */
    public static VMStore loadForPlanning(@NonNull Context context) {
        try {
            var client = new VmConfigClient(request -> DaemonConnection.getInstance().request(request));
            var store = new VMStore();
            boolean loaded = client.supported()
                ? store.load(client.read().document()) : store.load(context);
            if (!loaded) throw new IOException("Cannot read VM disk dependencies");
            return store;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read VM disk dependencies", error);
        }
    }

    /**
     * Call on a worker. Returns true only after all planned slots were matched and saved.
     * This is the configuration commit, not a lease over subsequent disk file operations.
     */
    public static boolean applyPlan(@NonNull Context context, @NonNull CursorPlan plan) {
        var changes = plan.persistedChanges();
        if (changes.isEmpty()) return true;
        try {
            var client = new VmConfigClient(request -> DaemonConnection.getInstance().request(request));
            if (client.supported()) {
                var session = client.edit();
                var vmStore = new VMStore(session.document());
                var updates = applyChanges(vmStore, changes);
                session.saveVms(updates);
            } else {
                VmConfigOwnership.legacyWrite(context.getFilesDir(), () -> {
                    var vmStore = new VMStore();
                    if (!vmStore.load(context)) throw new IOException("Cannot read VM disk dependencies");
                    var updates = applyChanges(vmStore, changes);
                    if (updates.length() != 0 && !vmStore.save(context))
                        throw new IOException("Cannot save VM disk dependencies");
                });
            }
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Failed to commit VM disk dependencies; keeping disk files", error);
            return false;
        }
    }

    private static JSONArray applyChanges(VMStore vmStore, List<CursorPlan.Change> changes) throws Exception {
        var byVm = new HashMap<UUID, List<CursorPlan.Change>>();
        for (var c : changes) {
            if (c.from.vmId == null) throw new IOException("Persisted disk change has no VM ID");
            byVm.computeIfAbsent(c.from.vmId, k -> new ArrayList<>()).add(c);
        }
        // Validate the complete plan before mutating any of its VM copies.
        for (var e : byVm.entrySet()) {
            var vm = vmStore.findById(e.getKey());
            if (vm == null) throw new IOException("VM disappeared since disk planning");
            var disks = vm.item.opt("disks", null);
            if (disks == null || !matchesPlan(disks, e.getValue()))
                throw new IOException("VM disk slots changed since planning");
        }
        var updates = new JSONArray();
        for (var e : byVm.entrySet()) {
            var vm = vmStore.findById(e.getKey());
            if (applyToDisks(vm.item.opt("disks", null), e.getValue())) updates.put(vm.toJson());
        }
        return updates;
    }

    static boolean matchesPlan(DataItem disks, List<CursorPlan.Change> changes) {
        if (!disks.is(DataItem.Type.ARRAY)) return false;
        var slots = new HashSet<Integer>();
        for (var change : changes) {
            int slot = change.from.slot;
            if (!slots.add(slot) || slot < 0 || slot >= disks.size() || change.from.path == null
                || !change.from.path.equals(disks.get(slot).optString("path", ""))) return false;
        }
        return true;
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
