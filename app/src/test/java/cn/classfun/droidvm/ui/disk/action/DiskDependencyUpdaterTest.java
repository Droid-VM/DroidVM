// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursor;
import cn.classfun.droidvm.ui.disk.tree.CursorPlan;
import cn.classfun.droidvm.ui.disk.tree.TreeShape;

public final class DiskDependencyUpdaterTest {
    private static final UUID VM = UUID.randomUUID();
    private static final UUID R = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    private static DataItem slot(String path, boolean readOnly) {
        var slot = DataItem.newObject();
        slot.set("path", path);
        slot.set("readonly", readOnly);
        return slot;
    }

    /** R -> C -> D. */
    private static TreeShape chain() {
        return TreeShape.empty()
            .put(R, "/r.qcow2", "r.qcow2", null)
            .put(C, "/c.qcow2", "c.qcow2", R)
            .put(D, "/d.qcow2", "d.qcow2", C);
    }

    private static AttachmentCursor at(int slot, TreeShape shape, UUID node, boolean ro) {
        return new AttachmentCursor(AttachmentCursor.Kind.PERSISTED, VM, "vm", slot, node,
            shape.pathOf(node), ro, false);
    }

    @Test
    public void mergeMovesOnlyDirectOverlaySlotsAndLocksTheBase() {
        var disks = DataItem.newArray();
        disks.append(slot("/c.qcow2", false));
        disks.append(slot("/d.qcow2", false));
        var before = chain();
        var plan = CursorPlan.reconcile(
            List.of(at(0, before, C, false), at(1, before, D, false)),
            List.of(), before, before.withMerged(C));

        assertTrue(DiskDependencyUpdater.applyToDisks(disks, plan.persistedChanges()));

        assertEquals("/r.qcow2", disks.get(0).optString("path", ""));
        assertTrue("R now carries D", disks.get(0).optBoolean("readonly", false));
        assertEquals("/d.qcow2", disks.get(1).optString("path", ""));
        assertFalse(disks.get(1).optBoolean("readonly", false));
    }

    @Test
    public void deleteRedirectsEverySubtreeSlotToSelectedNodesParent() {
        var disks = DataItem.newArray();
        disks.append(slot("/c.qcow2", false));
        disks.append(slot("/d.qcow2", true));
        disks.append(slot("/other.qcow2", false));
        var before = chain();
        var plan = CursorPlan.reconcile(
            List.of(at(0, before, C, false), at(1, before, D, true)),
            List.of(), before, before.without(before.subtreeOf(C)));

        assertTrue(DiskDependencyUpdater.applyToDisks(disks, plan.persistedChanges()));

        assertEquals("/r.qcow2", disks.get(0).optString("path", ""));
        assertEquals("/r.qcow2", disks.get(1).optString("path", ""));
        // Two slots on one disk: both read-only now.
        assertTrue(disks.get(0).optBoolean("readonly", false));
        assertTrue(disks.get(1).optBoolean("readonly", false));
        assertEquals("/other.qcow2", disks.get(2).optString("path", ""));
    }

    @Test
    public void deletingRootRemovesOnlyAffectedSlotsHighestFirst() {
        var disks = DataItem.newArray();
        disks.append(slot("/r.qcow2", false));
        disks.append(slot("/keep.qcow2", true));
        disks.append(slot("/d.qcow2", false));
        var before = chain();
        var plan = CursorPlan.reconcile(
            List.of(at(0, before, R, false), at(2, before, D, false)),
            List.of(), before, before.without(Set.of(R, C, D)));

        assertTrue(DiskDependencyUpdater.applyToDisks(disks, plan.persistedChanges()));

        assertEquals(1, disks.size());
        assertEquals("/keep.qcow2", disks.get(0).optString("path", ""));
    }

    @Test
    public void slotChangedSincePlanningIsLeftAlone() {
        var disks = DataItem.newArray();
        disks.append(slot("/somewhere-else.qcow2", false));
        var before = chain();
        var plan = CursorPlan.reconcile(
            List.of(at(0, before, C, false)),
            List.of(), before, before.without(before.subtreeOf(C)));

        assertFalse(DiskDependencyUpdater.applyToDisks(disks, plan.persistedChanges()));

        assertEquals("/somewhere-else.qcow2", disks.get(0).optString("path", ""));
    }

    @Test
    public void readonlyIsNeverTakenAway() {
        var disks = DataItem.newArray();
        disks.append(slot("/c.qcow2", true));
        var before = chain().without(Set.of(D)); // R -> C, C a leaf
        var plan = CursorPlan.reconcile(
            List.of(at(0, before, C, true)),
            List.of(), before, before.without(Set.of(C)));

        assertTrue(DiskDependencyUpdater.applyToDisks(disks, plan.persistedChanges()));

        assertEquals("/r.qcow2", disks.get(0).optString("path", ""));
        assertTrue(disks.get(0).optBoolean("readonly", false));
    }
}
