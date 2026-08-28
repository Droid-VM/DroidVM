// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/** Keeps persisted VM disk slots valid while disk-tree nodes are removed or merged. */
public final class DiskDependencyUpdater {
    private static final String TAG = "DiskDependencyUpdater";

    private DiskDependencyUpdater() {
    }

    /**
     * Redirect every VM disk slot whose path is in {@code sourcePaths}. A null target removes the
     * slot (used when deleting a root). Slot order and all non-path settings are preserved.
     * Returns only after the updated VMStore is durably saved; failures are fail-closed.
     */
    public static boolean redirectVmDisks(
        @NonNull Context context,
        @NonNull Set<String> sourcePaths,
        @Nullable String targetPath
    ) {
        var vmStore = new VMStore();
        if (!vmStore.load(vmStore, context)) {
            Log.e(TAG, "Failed to load VM store before updating disk dependencies");
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < vmStore.size(); i++) {
            var disks = vmStore.get(i).item.opt("disks", null);
            if (disks == null || !disks.is(DataItem.Type.ARRAY)) continue;
            changed |= rewriteDiskSlots(disks, sourcePaths, targetPath);
        }
        if (!changed) return true;
        if (vmStore.save(context)) return true;
        Log.e(TAG, "Failed to save redirected VM disk dependencies");
        return false;
    }

    /** Pure slot rewrite kept separate for deterministic unit coverage. */
    static boolean rewriteDiskSlots(
        @NonNull DataItem disks,
        @NonNull Set<String> sourcePaths,
        @Nullable String targetPath
    ) {
        boolean changed = false;
        for (int i = disks.asArray().size() - 1; i >= 0; i--) {
            var disk = disks.asArray().get(i);
            if (!sourcePaths.contains(disk.optString("path", ""))) continue;
            if (targetPath == null) {
                disks.remove(i);
            } else {
                disk.set("path", targetPath);
            }
            changed = true;
        }
        return changed;
    }
}
