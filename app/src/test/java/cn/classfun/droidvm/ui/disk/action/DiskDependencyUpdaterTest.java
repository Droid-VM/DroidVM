// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

import cn.classfun.droidvm.lib.store.base.DataItem;

public final class DiskDependencyUpdaterTest {
    private static DataItem slot(String path, boolean readOnly) {
        var slot = DataItem.newObject();
        slot.set("path", path);
        slot.set("readonly", readOnly);
        return slot;
    }

    @Test
    public void mergeMovesOnlyDirectOverlaySlots() {
        var disks = DataItem.newArray();
        disks.append(slot("/base/overlay.qcow2", true));
        disks.append(slot("/base/child.qcow2", false));

        assertTrue(DiskDependencyUpdater.rewriteDiskSlots(
            disks, Set.of("/base/overlay.qcow2"), "/base/base.qcow2"));

        assertEquals("/base/base.qcow2", disks.get(0).optString("path", ""));
        assertTrue(disks.get(0).optBoolean("readonly", false));
        assertEquals("/base/child.qcow2", disks.get(1).optString("path", ""));
    }

    @Test
    public void deleteRedirectsEverySubtreeSlotToSelectedNodesParent() {
        var disks = DataItem.newArray();
        disks.append(slot("/base/overlay.qcow2", false));
        disks.append(slot("/base/child.qcow2", true));
        disks.append(slot("/other.qcow2", false));

        assertTrue(DiskDependencyUpdater.rewriteDiskSlots(
            disks,
            Set.of("/base/overlay.qcow2", "/base/child.qcow2"),
            "/base/base.qcow2"));

        assertEquals("/base/base.qcow2", disks.get(0).optString("path", ""));
        assertEquals("/base/base.qcow2", disks.get(1).optString("path", ""));
        assertEquals("/other.qcow2", disks.get(2).optString("path", ""));
    }

    @Test
    public void deletingRootRemovesOnlyAffectedSlots() {
        var disks = DataItem.newArray();
        disks.append(slot("/tree/root.qcow2", false));
        disks.append(slot("/keep.qcow2", true));
        disks.append(slot("/tree/child.qcow2", false));

        assertTrue(DiskDependencyUpdater.rewriteDiskSlots(
            disks, Set.of("/tree/root.qcow2", "/tree/child.qcow2"), null));

        assertEquals(1, disks.asArray().size());
        assertEquals("/keep.qcow2", disks.get(0).optString("path", ""));
    }
}
