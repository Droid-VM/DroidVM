// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;

public class MotionSensorsTest {
    private static DataItem withRow(boolean correct) {
        var item = DataItem.newObject();
        var row = VMPeripheralConfig.createDefaultGyroscope();
        row.setCorrect(correct);
        VMXhciConfig.peripherals(item).append(row.item);
        return item;
    }

    private static int rows(DataItem item) {
        int n = 0;
        for (var peripheral : VMPeripheralConfig.listOf(item))
            if (peripheral.getType() == PeripheralType.VIRTIO_GYROSCOPE) n++;
        return n;
    }

    @Test
    public void theRowIsTheSetting() {
        assertTrue(MotionSensors.enabled(withRow(true)));
        assertTrue(MotionSensors.corrected(withRow(true)));
        assertTrue(MotionSensors.enabled(withRow(false)));
        assertFalse(MotionSensors.corrected(withRow(false)));
        // A new row corrects, as a VM-level switch with no correction setting did.
        assertTrue(VMPeripheralConfig.createDefaultGyroscope().getCorrect());
    }

    @Test
    public void legacySwitchesReadAsBeforeUntilMigrated() {
        var on = DataItem.newObject();
        on.set(MotionSensors.LEGACY_KEY, true);
        assertTrue(MotionSensors.enabled(on));
        assertTrue(MotionSensors.corrected(on));
        on.set(MotionSensors.LEGACY_KEY_CORRECT, false);
        assertFalse(MotionSensors.corrected(on));
        var off = DataItem.newObject();
        off.set(MotionSensors.LEGACY_KEY, false);
        off.set(MotionSensors.LEGACY_KEY_CORRECT, true);
        assertFalse(MotionSensors.enabled(off));
        assertFalse(MotionSensors.corrected(off));
    }

    @Test
    public void migrateMovesTheSwitchesOntoOneRow() {
        var item = DataItem.newObject();
        item.set(MotionSensors.LEGACY_KEY, true);
        item.set(MotionSensors.LEGACY_KEY_CORRECT, false);
        MotionSensors.migrate(item);
        assertNull(item.opt(MotionSensors.LEGACY_KEY, (DataItem) null));
        assertNull(item.opt(MotionSensors.LEGACY_KEY_CORRECT, (DataItem) null));
        var row = MotionSensors.rowOf(item);
        assertNotNull(row);
        assertFalse(row.getCorrect());
        assertTrue(MotionSensors.enabled(item));
        assertFalse(MotionSensors.corrected(item));
        MotionSensors.migrate(item);
        assertEquals(1, rows(item));
    }

    @Test
    public void migrateDropsSwitchesThatWereOffOrAlreadyHaveARow() {
        var off = DataItem.newObject();
        off.set(MotionSensors.LEGACY_KEY, false);
        MotionSensors.migrate(off);
        assertNull(off.opt(MotionSensors.LEGACY_KEY, (DataItem) null));
        assertFalse(MotionSensors.enabled(off));
        assertEquals(0, rows(off));

        var both = withRow(true);
        both.set(MotionSensors.LEGACY_KEY, true);
        both.set(MotionSensors.LEGACY_KEY_CORRECT, false);
        MotionSensors.migrate(both);
        assertEquals(1, rows(both));
        assertTrue(MotionSensors.corrected(both));
    }
}
