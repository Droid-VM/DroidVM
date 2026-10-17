// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.disk.action;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursor;
import cn.classfun.droidvm.ui.disk.tree.CursorPlan;
import cn.classfun.droidvm.ui.disk.tree.TreeShape;

public final class DiskJointPlanTest {
    private final UUID vm = UUID.randomUUID(), disk = UUID.randomUUID();

    private JSONObject document() throws Exception {
        return new JSONObject().put("future_root", 9007199254740993L).put("vms", new JSONArray()
            .put(new JSONObject().put("id", vm.toString()).put("name", "VM").put("future_vm", "keep")
                .put("disks", new JSONArray().put(new JSONObject().put("path", "/disk.qcow2"))))) ;
    }

    private CursorPlan plan(boolean pinned) {
        var shape = TreeShape.empty().put(disk, "/disk.qcow2", "disk", null);
        var cursor = new AttachmentCursor(AttachmentCursor.Kind.PERSISTED, vm, "VM", 0, disk,
            "/disk.qcow2", false, pinned);
        return CursorPlan.reconcile(List.of(cursor), List.of(), shape, shape.without(Set.of(disk)));
    }

    @Test public void detachPreservesRawFieldsAndDoesNotMutateBaseline() throws Exception {
        var baseline = document(); String original = baseline.toString();
        var result = DiskDependencyUpdater.applyToDocument(baseline, plan(false));
        assertEquals(original, baseline.toString());
        assertEquals(9007199254740993L, result.getLong("future_root"));
        var row = result.getJSONArray("vms").getJSONObject(0);
        assertEquals(0, row.getJSONArray("disks").length());
        assertEquals("keep", row.getString("future_vm"));
        assertEquals(4, row.length()); // No VMStore migrations/defaults leak into the raw document.
    }

    @Test public void staleSlotRejectsWithoutChangingInput() throws Exception {
        var baseline = document();
        baseline.getJSONArray("vms").getJSONObject(0).getJSONArray("disks").getJSONObject(0).put("path", "/other");
        String original = baseline.toString();
        assertThrows(IOException.class, () -> DiskDependencyUpdater.applyToDocument(baseline, plan(false)));
        assertEquals(original, baseline.toString());
    }

    @Test public void refusedPlanCannotBecomeAnEmptySuccessfulEdit() throws Exception {
        var baseline = document();
        assertTrue(plan(true).isRefused());
        assertThrows(IOException.class, () -> DiskDependencyUpdater.applyToDocument(baseline, plan(true)));
    }
}
