// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;

public final class VMStoreFieldUpdateTest {
    private static DataItem boot(String kernel) {
        var boot = DataItem.newObject();
        boot.set("kernel", kernel);
        return boot;
    }

    @Test
    public void onlyChosenFieldIsSavedAndConcurrentUnrelatedEditsSurvive() throws Exception {
        var current = DataItem.newObject();
        current.set("name", "renamed elsewhere");
        current.set("protected_vm", "protected");
        current.set("boot", boot("old"));
        var expected = DataItem.newObject();
        expected.set("name", "old name");
        expected.set("protected_vm", "pseudo_unprotected"); // Session-only override.
        expected.set("boot", boot("old"));
        var changed = DataItem.newObject();
        changed.set("protected_vm", "pseudo_unprotected");
        changed.set("boot", boot("new"));
        VMStore.applyFieldUpdate(current, expected, changed, "boot");
        assertEquals("new", current.get("boot").optString("kernel", ""));
        assertEquals("old", expected.get("boot").optString("kernel", ""));
        assertEquals("renamed elsewhere", current.optString("name", ""));
        assertEquals("protected", current.optString("protected_vm", ""));
    }

    @Test
    public void concurrentNestedEditRejectsWithoutChangingCurrent() {
        var current = DataItem.newObject();
        current.set("boot", boot("concurrent"));
        var expected = DataItem.newObject();
        expected.set("boot", boot("old"));
        var changed = DataItem.newObject();
        changed.set("boot", boot("new"));
        assertThrows(IOException.class, () -> VMStore.applyFieldUpdate(current, expected, changed, "boot"));
        assertEquals("concurrent", current.get("boot").optString("kernel", ""));
    }

    @Test
    public void reorderedDiskSlotsRejectReadonlyCorrection() {
        var first = DataItem.newObject(); first.set("path", "/first"); first.set("readonly", false);
        var second = DataItem.newObject(); second.set("path", "/second"); second.set("readonly", false);
        var expectedDisks = DataItem.newArray(); expectedDisks.append(first); expectedDisks.append(second);
        var currentDisks = DataItem.newArray(); currentDisks.append(second); currentDisks.append(first);
        var expected = DataItem.newObject(); expected.set("disks", expectedDisks);
        var current = DataItem.newObject(); current.set("disks", currentDisks);
        var changed = DataItem.newObject(); changed.set("disks", DataItem.newArray());
        assertThrows(IOException.class, () -> VMStore.applyFieldUpdate(current, expected, changed, "disks"));
        assertEquals("/second", current.get("disks").get(0).optString("path", ""));
        assertFalse(current.get("disks").get(0).optBoolean("readonly", true));
    }

    @Test
    public void missingFieldCanBeAddedButExplicitNullIsDistinct() throws Exception {
        var current = DataItem.newObject();
        var expected = DataItem.newObject();
        var changed = DataItem.newObject(); changed.set("mode", "single");
        VMStore.applyFieldUpdate(current, expected, changed, "mode");
        assertEquals("single", current.optString("mode", ""));
        current.set("mode", DataItem.newNull());
        assertThrows(IOException.class, () -> VMStore.applyFieldUpdate(current, expected, changed, "mode"));
        assertTrue(current.get("mode").is(DataItem.Type.NULL));
    }

    @Test
    public void deletionIsExplicitAndIdentityCannotBeChanged() throws Exception {
        var current = DataItem.newObject(); current.set("mode", "old"); current.set("id", "identity");
        var expected = DataItem.newObject(); expected.set("mode", "old"); expected.set("id", "identity");
        var changed = DataItem.newObject();
        VMStore.applyFieldUpdate(current, expected, changed, "mode");
        assertNull(current.opt("mode", null));
        assertThrows(IOException.class, () -> VMStore.applyFieldUpdate(current, expected, changed, "id"));
        assertEquals("identity", current.optString("id", ""));
    }
}
