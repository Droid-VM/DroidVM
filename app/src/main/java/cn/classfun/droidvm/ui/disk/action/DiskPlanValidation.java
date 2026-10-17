// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.disk.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursor;

/** Checks an unchanged confirmation baseline. This is not a file or runtime lease. */
final class DiskPlanValidation {
    private DiskPlanValidation() {}

    static boolean hasSurvivingPathAlias(DiskStore store, Set<UUID> removed) {
        var paths = new HashSet<String>();
        for (int i = 0; i < store.size(); i++)
            if (removed.contains(store.get(i).getId())) paths.add(store.get(i).getFullPath());
        for (int i = 0; i < store.size(); i++)
            if (!removed.contains(store.get(i).getId()) && paths.contains(store.get(i).getFullPath())) return true;
        return false;
    }

    static boolean sameRegistry(DiskStore before, DiskStore now) {
        if (before.size() != now.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            var expected = before.get(i);
            var actual = now.findById(expected.getId());
            if (actual == null || !expected.item.equals(actual.item)) return false;
        }
        return true;
    }

    static boolean sameCursors(List<AttachmentCursor> before, List<AttachmentCursor> now) {
        if (before.size() != now.size()) return false;
        var remaining = new ArrayList<>(now);
        for (var expected : before) {
            int match = -1;
            for (int i = 0; i < remaining.size(); i++) {
                var actual = remaining.get(i);
                if (expected.kind == actual.kind && expected.slot == actual.slot
                        && expected.readonly == actual.readonly && expected.pinned == actual.pinned
                        && Objects.equals(expected.vmId, actual.vmId)
                        && Objects.equals(expected.vmName, actual.vmName)
                        && Objects.equals(expected.nodeId, actual.nodeId)
                        && Objects.equals(expected.path, actual.path)) {
                    match = i;
                    break;
                }
            }
            if (match < 0) return false;
            remaining.remove(match);
        }
        return true;
    }
}
