// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.disk.action;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursor;

public final class DiskPlanValidationTest {
    private static final UUID DISK = UUID.randomUUID(), CHILD = UUID.randomUUID(), VM = UUID.randomUUID();

    private static DiskStore registry(boolean child) {
        var store = new DiskStore();
        var root = new DiskConfig(); root.setId(DISK); root.setName("disk.qcow2");
        root.item.set("folder", "/images"); store.add(root);
        if (child) {
            var overlay = new DiskConfig(); overlay.setId(CHILD); overlay.setName("child.qcow2");
            overlay.item.set("folder", "/images"); overlay.setParentId(DISK); store.add(overlay);
        }
        return store;
    }

    private static AttachmentCursor cursor(int slot) {
        return new AttachmentCursor(AttachmentCursor.Kind.PERSISTED, VM, "vm", slot,
            DISK, "/images/disk.qcow2", false, false);
    }

    @Test public void addedOverlayInvalidatesDeletion() {
        assertFalse(DiskPlanValidation.sameRegistry(registry(false), registry(true)));
    }

    @Test public void changedPathOrBrokenParentInvalidatesDeletion() {
        var before = registry(false); var after = registry(false);
        after.get(0).item.set("folder", "/moved");
        assertFalse(DiskPlanValidation.sameRegistry(before, after));
        after = registry(false); after.get(0).item.set("parent", "unresolved-parent");
        assertFalse(DiskPlanValidation.sameRegistry(before, after));
    }

    @Test public void registryOrderDoesNotChangeThePlan() {
        var before = registry(true); var after = new DiskStore();
        after.add(before.get(1)); after.add(before.get(0));
        assertTrue(DiskPlanValidation.sameRegistry(before, after));
    }

    @Test public void newReferenceAndStartedVmInvalidateConfirmation() {
        assertFalse(DiskPlanValidation.sameCursors(List.of(), List.of(cursor(0))));
        assertFalse(DiskPlanValidation.sameCursors(List.of(cursor(0)), List.of(cursor(0).withPinned(true))));
        assertFalse(DiskPlanValidation.sameCursors(List.of(cursor(0)),
            List.of(cursor(0).at(DISK, "/images/disk.qcow2", true))));
    }

    @Test public void reorderedCursorsMatchButDuplicateSlotsDoNot() {
        assertTrue(DiskPlanValidation.sameCursors(List.of(cursor(0), cursor(1)), List.of(cursor(1), cursor(0))));
        assertFalse(DiskPlanValidation.sameCursors(List.of(cursor(0), cursor(1)), List.of(cursor(0), cursor(0))));
    }

    @Test public void survivingEntryAtTheSamePathPreventsFileRemoval() {
        var store = registry(false);
        var alias = new DiskConfig(); alias.setId(CHILD); alias.setName("disk.qcow2");
        alias.item.set("folder", "/images/"); store.add(alias);
        assertTrue(DiskPlanValidation.hasSurvivingPathAlias(store, Set.of(DISK)));
        assertFalse(DiskPlanValidation.hasSurvivingPathAlias(store, Set.of(DISK, CHILD)));
        alias.item.set("folder", "/other");
        assertFalse(DiskPlanValidation.hasSurvivingPathAlias(store, Set.of(DISK)));
    }
}
