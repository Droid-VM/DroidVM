// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which line of a rule card says what. The ordering is the whole of this class and it is the
 * part a screenshot cannot check: the port zone and the device zone show the same two values in
 * opposite roles, so getting them the wrong way round produces a card that looks right and
 * means the other thing.
 */
public final class UsbRuleLinesTest {
    @Test
    public void theExactZoneLeadsWithBothOfItsMatchers() {
        assertArrayEquals(new UsbRuleLines.Slot[]{
            UsbRuleLines.Slot.MATCH_ID,
            UsbRuleLines.Slot.MATCH_PORT,
            UsbRuleLines.Slot.NAME,
        }, UsbRuleLines.of(UsbRuleLayer.EXACT));
    }

    /** A port rule is about a socket; which device is in it is context, and the other way round. */
    @Test
    public void theOtherTwoZonesLeadWithTheirOwnMatcher() {
        assertArrayEquals(new UsbRuleLines.Slot[]{
            UsbRuleLines.Slot.MATCH_PORT,
            UsbRuleLines.Slot.INFO_ID,
            UsbRuleLines.Slot.NAME,
        }, UsbRuleLines.of(UsbRuleLayer.PORT));
        assertArrayEquals(new UsbRuleLines.Slot[]{
            UsbRuleLines.Slot.MATCH_ID,
            UsbRuleLines.Slot.INFO_PORT,
            UsbRuleLines.Slot.NAME,
        }, UsbRuleLines.of(UsbRuleLayer.DEVICE));
    }

    @Test
    public void theCatchAllZoneSaysOneThing() {
        assertArrayEquals(new UsbRuleLines.Slot[]{
            UsbRuleLines.Slot.MATCH_ANY,
            UsbRuleLines.Slot.NONE,
            UsbRuleLines.Slot.NONE,
        }, UsbRuleLines.of(UsbRuleLayer.ANY));
    }

    @Test
    public void everyZoneFillsTheCard() {
        for (var layer : UsbRuleLayer.values())
            assertEquals(layer.key, UsbRuleLines.LINES, UsbRuleLines.of(layer).length);
    }

    /**
     * A line is bold exactly when it is the rule, and editable exactly when it is a field the
     * rule stores. The catch-all's line is the first without the second: it is the matcher, and
     * there is nothing about it to pick.
     */
    @Test
    public void onlyAMatcherIsBoldAndOnlyAStoredFieldOpensAPicker() {
        for (var layer : UsbRuleLayer.values())
            for (var slot : UsbRuleLines.of(layer)) {
                if (slot.edits != null) assertTrue(slot.name(), slot.strong);
                if (!slot.strong) assertNull(slot.name(), slot.edits);
            }
        assertTrue(UsbRuleLines.Slot.MATCH_ANY.strong);
        assertNull(UsbRuleLines.Slot.MATCH_ANY.edits);
        assertEquals(UsbRuleLines.Field.ID, UsbRuleLines.Slot.MATCH_ID.edits);
        assertEquals(UsbRuleLines.Field.PORT, UsbRuleLines.Slot.MATCH_PORT.edits);
        assertFalse(UsbRuleLines.Slot.NONE.strong);
    }

    /**
     * The card never shows a field the zone does not store as if the rule matched on it: a port
     * zone card carries no MATCH_ID and a device zone card no MATCH_PORT, which is the same
     * statement UsbRuleLayer makes with hasId and hasPort.
     */
    @Test
    public void aZoneOnlyMatchesOnTheFieldsItStores() {
        for (var layer : UsbRuleLayer.values())
            for (var slot : UsbRuleLines.of(layer)) {
                if (slot == UsbRuleLines.Slot.MATCH_ID) assertTrue(layer.key, layer.hasId);
                if (slot == UsbRuleLines.Slot.MATCH_PORT) assertTrue(layer.key, layer.hasPort);
            }
    }
}
