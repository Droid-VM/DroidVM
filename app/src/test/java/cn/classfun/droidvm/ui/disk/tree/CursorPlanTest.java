// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.ui.disk.tree.AttachmentCursor.Kind;

public final class CursorPlanTest {
    private static final UUID R = UUID.randomUUID();
    private static final UUID C1 = UUID.randomUUID();
    private static final UUID C2 = UUID.randomUUID();
    private static final UUID D1 = UUID.randomUUID();
    private static final UUID N = UUID.randomUUID();

    /** R -> C1 -> D1, R -> C2. */
    private static TreeShape family() {
        return TreeShape.empty()
            .put(R, "/r.qcow2", "r.qcow2", null)
            .put(C1, "/c1.qcow2", "c1.qcow2", R)
            .put(D1, "/d1.qcow2", "d1.qcow2", C1)
            .put(C2, "/c2.qcow2", "c2.qcow2", R);
    }

    private static AttachmentCursor cursor(
        Kind kind, String vm, int slot, TreeShape shape, UUID node, boolean ro, boolean pinned) {
        return new AttachmentCursor(kind, UUID.randomUUID(), vm, slot, node,
            shape.pathOf(node), ro, pinned);
    }

    private static AttachmentCursor persisted(String vm, TreeShape s, UUID node, boolean ro) {
        return cursor(Kind.PERSISTED, vm, 0, s, node, ro, false);
    }

    @Test
    public void deleteClimbsToParentAndLocksUnderRemainingSibling() {
        var before = family();
        var after = before.without(before.subtreeOf(C2));
        var a = persisted("a", before, C2, false);

        var plan = CursorPlan.reconcile(List.of(a), List.of(), before, after);

        assertFalse(plan.isRefused());
        assertEquals(1, plan.changes.size());
        var to = plan.changes.get(0).to;
        assertEquals(R, to.nodeId);
        assertEquals("/r.qcow2", to.path);
        assertTrue("R still has C1 under it", to.readonly);
        assertTrue(plan.changes.get(0).moved());
    }

    @Test
    public void deleteOntoALeafParentKeepsWritable() {
        var before = TreeShape.empty()
            .put(R, "/r.qcow2", "r.qcow2", null)
            .put(C1, "/c1.qcow2", "c1.qcow2", R);
        var after = before.without(Set.of(C1));
        var a = persisted("a", before, C1, false);

        var plan = CursorPlan.reconcile(List.of(a), List.of(), before, after);

        assertEquals(R, plan.cursors.get(0).nodeId);
        assertFalse(plan.cursors.get(0).readonly);
    }

    @Test
    public void climbsSeveralLevelsWhenIntermediatesAreGone() {
        var before = family();
        var after = before.without(before.subtreeOf(C1)); // C1 and D1 go
        var a = persisted("a", before, D1, false);

        var plan = CursorPlan.reconcile(List.of(a), List.of(), before, after);

        assertEquals(R, plan.cursors.get(0).nodeId);
    }

    @Test
    public void rootDeleteClearsEveryCursor() {
        var before = family();
        var after = before.without(before.familyOf(D1));
        var a = persisted("a", before, D1, false);
        var b = persisted("b", before, R, true);

        var plan = CursorPlan.reconcile(List.of(a, b), List.of(), before, after);

        assertEquals(2, plan.changes.size());
        for (var c : plan.changes) {
            assertTrue(c.cleared());
            assertNull(c.to.path);
        }
    }

    @Test
    public void twoCursorsMeetingOnOneNodeAreBothReadonly() {
        // b already sits on R (read-only, R is a base); a arrives from C2 after its delete and
        // R becomes a leaf - yet two holders means a is forced read-only too.
        var before = TreeShape.empty()
            .put(R, "/r.qcow2", "r.qcow2", null)
            .put(C2, "/c2.qcow2", "c2.qcow2", R);
        var after = before.without(Set.of(C2));
        var a = persisted("a", before, C2, false);
        var b = persisted("b", before, R, true);

        var plan = CursorPlan.reconcile(List.of(a, b), List.of(), before, after);

        assertEquals(1, plan.changes.size());
        var change = plan.changes.get(0);
        assertSame(a, change.from);
        assertEquals(R, change.to.nodeId);
        assertTrue(change.to.readonly);
        assertTrue(change.readonlyForced());
    }

    @Test
    public void twoWritersAlreadySharingAreForcedEvenWithoutAMove() {
        var shape = TreeShape.empty().put(R, "/r.qcow2", "r.qcow2", null);
        var a = persisted("a", shape, R, false);
        var b = persisted("b", shape, R, false);

        var plan = CursorPlan.reconcile(List.of(a, b), List.of(), shape, shape);

        assertEquals(2, plan.changes.size());
        for (var c : plan.changes) {
            assertFalse(c.moved());
            assertTrue(c.readonlyForced());
        }
    }

    @Test
    public void fixedCursorsCountTowardsSharing() {
        var before = TreeShape.empty()
            .put(R, "/r.qcow2", "r.qcow2", null)
            .put(C2, "/c2.qcow2", "c2.qcow2", R);
        var after = before.without(Set.of(C2));
        var a = persisted("a", before, C2, false);
        var fixedOnR = persisted("b", after, R, true);

        var plan = CursorPlan.reconcile(List.of(a), List.of(fixedOnR), before, after);

        assertTrue(plan.cursors.get(0).readonly);
    }

    @Test
    public void shadowDoesNotDoubleCountItsEditorRow() {
        var shape = TreeShape.empty().put(R, "/r.qcow2", "r.qcow2", null);
        var vmId = UUID.randomUUID();
        var live = new AttachmentCursor(Kind.ACTIVE, vmId, "a", 0, R, "/r.qcow2", false, false);
        var shadow = new AttachmentCursor(Kind.SHADOW, vmId, "a", 0, R, "/r.qcow2", false, false);

        var plan = CursorPlan.reconcile(List.of(live, shadow), List.of(), shape, shape);

        assertTrue(plan.changes.isEmpty());
    }

    @Test
    public void twoEditorRowsOnOneDiskAreBothForced() {
        var shape = TreeShape.empty().put(R, "/r.qcow2", "r.qcow2", null);
        var row0 = new AttachmentCursor(Kind.ACTIVE, null, "new", 0, R, "/r.qcow2", false, false);
        var row1 = new AttachmentCursor(Kind.EDITOR, null, "new", 1, R, "/r.qcow2", false, false);

        var plan = CursorPlan.reconcile(List.of(row0, row1), List.of(), shape, shape);

        assertEquals(2, plan.liveChanges().size());
        assertTrue(plan.persistedChanges().isEmpty());
        assertTrue(plan.cursors.get(0).readonly);
        assertTrue(plan.cursors.get(1).readonly);
    }

    @Test
    public void mergeMovesOnlyDirectHoldersOntoTheBase() {
        var before = TreeShape.empty()
            .put(R, "/r.qcow2", "r.qcow2", null)
            .put(C1, "/c1.qcow2", "c1.qcow2", R)
            .put(D1, "/d1.qcow2", "d1.qcow2", C1);
        var after = before.withMerged(C1);
        var onC1 = persisted("a", before, C1, true);
        var onD1 = persisted("b", before, D1, false);

        var plan = CursorPlan.reconcile(List.of(onC1, onD1), List.of(), before, after);

        assertEquals(1, plan.changes.size());
        assertEquals(R, plan.changes.get(0).to.nodeId);
        assertTrue("R now carries D1", plan.changes.get(0).to.readonly);
        assertEquals(D1, plan.cursors.get(1).nodeId);
        assertEquals(R, after.parentOf(D1));
    }

    @Test
    public void firstOverlayUnderALeafTakesWritableCursorsAlong() {
        var before = TreeShape.empty().put(R, "/r.qcow2", "r.qcow2", null);
        var after = before.withChild(N, "/n.qcow2", "n.qcow2", R);
        var a = persisted("a", before, R, false);

        var plan = CursorPlan.reconcile(List.of(a), List.of(), before, after);

        assertEquals(1, plan.changes.size());
        var to = plan.changes.get(0).to;
        assertEquals(N, to.nodeId);
        assertEquals("/n.qcow2", to.path);
        assertFalse("the new leaf is the cursor's alone", to.readonly);
    }

    @Test
    public void readonlyCursorStaysOnTheNewBase() {
        var before = TreeShape.empty().put(R, "/r.qcow2", "r.qcow2", null);
        var after = before.withChild(N, "/n.qcow2", "n.qcow2", R);
        var a = persisted("a", before, R, true);

        var plan = CursorPlan.reconcile(List.of(a), List.of(), before, after);

        assertTrue(plan.changes.isEmpty());
        assertEquals(R, plan.cursors.get(0).nodeId);
    }

    @Test
    public void anotherOverlayUnderABaseMovesNothing() {
        var before = TreeShape.empty()
            .put(R, "/r.qcow2", "r.qcow2", null)
            .put(C1, "/c1.qcow2", "c1.qcow2", R);
        var after = before.withChild(N, "/n.qcow2", "n.qcow2", R);
        var a = persisted("a", before, R, true);

        var plan = CursorPlan.reconcile(List.of(a), List.of(), before, after);

        assertTrue(plan.changes.isEmpty());
    }

    @Test
    public void flattenMovesNothing() {
        var before = family();
        var after = before.withDetached(C1);
        var a = persisted("a", before, D1, false);
        var b = persisted("b", before, C1, true);

        var plan = CursorPlan.reconcile(List.of(a, b), List.of(), before, after);

        assertTrue(plan.changes.isEmpty());
        assertNull(after.parentOf(C1));
    }

    @Test
    public void pinnedCursorThatWouldChangeRefusesThePlan() {
        var before = family();
        var after = before.without(before.subtreeOf(C2));
        var a = cursor(Kind.PERSISTED, "a", 0, before, C2, false, true);

        var plan = CursorPlan.reconcile(List.of(a), List.of(), before, after);

        assertTrue(plan.isRefused());
        assertSame(a, plan.refused.get(0));
        assertSame("left untouched", a, plan.cursors.get(0));
        assertTrue(plan.changes.isEmpty());
    }

    @Test
    public void pinnedCursorElsewhereDoesNotRefuse() {
        var before = family();
        var after = before.without(before.subtreeOf(C2));
        var pinnedOnD1 = cursor(Kind.PERSISTED, "a", 0, before, D1, false, true);
        var b = persisted("b", before, C2, false);

        var plan = CursorPlan.reconcile(List.of(pinnedOnD1, b), List.of(), before, after);

        assertFalse(plan.isRefused());
        assertEquals(1, plan.changes.size());
        assertSame(b, plan.changes.get(0).from);
    }

    @Test
    public void announcedAndPersistedSplitByKind() {
        var before = family();
        var after = before.without(before.subtreeOf(C2));
        var vmId = UUID.randomUUID();
        var active = new AttachmentCursor(Kind.ACTIVE, vmId, "a", 0, C2, "/c2.qcow2", false, false);
        var shadow = new AttachmentCursor(Kind.SHADOW, vmId, "a", 0, C2, "/c2.qcow2", false, false);
        var other = persisted("b", before, C2, false);

        var plan = CursorPlan.reconcile(List.of(active, shadow, other), List.of(), before, after);

        assertEquals(3, plan.changes.size());
        assertEquals(1, plan.liveChanges().size());
        assertEquals(2, plan.persistedChanges().size());
        assertEquals(1, plan.announcedChanges().size());
        assertSame(other, plan.announcedChanges().get(0).from);
    }
}
