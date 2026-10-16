// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import org.junit.Test;
import cn.classfun.droidvm.lib.store.base.DataItem;

public final class VmStartPlanTest {
    private static DataItem config(boolean readonly, String protection) {
        var vm = DataItem.newObject(); vm.set("id", "vm"); vm.set("name", "name");
        vm.set("protected_vm", protection);
        var disk = DataItem.newObject(); disk.set("path", "/disk"); disk.set("readonly", readonly);
        var disks = DataItem.newArray(); disks.append(disk); vm.set("disks", disks);
        vm.set("future", "opaque");
        return vm;
    }

    @Test public void unchangedHasNoOverrides() throws Exception {
        var plan = VmStartPlan.between(config(false, "protected_normal"), config(false, "protected_normal"));
        assertTrue(plan.readonlyDisks.isEmpty()); assertFalse(plan.pseudoUnprotected);
    }

    @Test public void onlyTemporaryChangesBecomeOverridesWithoutMutatingBaseline() throws Exception {
        var before = config(false, "protected_normal");
        var after = config(true, "pseudo_unprotected");
        var plan = VmStartPlan.between(before, after);
        assertEquals(List.of(0), plan.readonlyDisks); assertTrue(plan.pseudoUnprotected);
        assertFalse(before.get("disks").get(0).optBoolean("readonly", true));
        assertEquals("protected_normal", before.optString("protected_vm", ""));
        assertThrows(UnsupportedOperationException.class, () -> plan.readonlyDisks.add(1));
    }

    @Test public void writableDowngradeRejected() {
        assertThrows(IOException.class, () -> VmStartPlan.between(config(true, "protected_normal"), config(false, "protected_normal")));
    }

    @Test public void pathChangeCannotHideBehindReadonlyOverride() {
        var selected = config(true, "protected_normal"); selected.get("disks").get(0).set("path", "/other");
        assertThrows(IOException.class, () -> VmStartPlan.between(config(false, "protected_normal"), selected));
    }

    @Test public void unknownFieldsAndIdentityMustMatch() {
        for (var key : List.of("id", "name", "future", "memory_mb", "networks", "boot")) {
            var selected = config(false, "protected_normal"); selected.set(key, "changed");
            assertThrows(IOException.class, () -> VmStartPlan.between(config(false, "protected_normal"), selected));
        }
    }

    @Test public void malformedReadonlyAndMissingOpaqueFieldRejected() {
        var selected = config(false, "protected_normal"); selected.get("disks").get(0).set("readonly", "true");
        assertThrows(IOException.class, () -> VmStartPlan.between(config(false, "protected_normal"), selected));
        var missing = config(false, "protected_normal"); missing.remove("future");
        assertThrows(IOException.class, () -> VmStartPlan.between(config(false, "protected_normal"), missing));
    }
}
