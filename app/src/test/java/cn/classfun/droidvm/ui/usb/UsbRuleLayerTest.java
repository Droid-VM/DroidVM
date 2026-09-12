// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The parts of the rules page that are plain logic: how a daemon names a layer, and how the
 * page derives a rule id and port path when a daemon does not send them (plan section 2.4).
 * Everything that touches org.json stays out -- the stubbed android.jar makes it inert here.
 */
public final class UsbRuleLayerTest {
    @Test
    public void layersAreOrderedAndNumberedAsTheDaemonTriesThem() {
        assertEquals(1, UsbRuleLayer.EXACT.number());
        assertEquals(2, UsbRuleLayer.PORT.number());
        assertEquals(3, UsbRuleLayer.DEVICE.number());
        assertEquals(4, UsbRuleLayer.ANY.number());
    }

    @Test
    public void wireKeysRoundTrip() {
        for (var layer : UsbRuleLayer.values())
            assertEquals(layer, UsbRuleLayer.fromKey(layer.key));
        assertNull(UsbRuleLayer.fromKey("hub"));
        assertNull(UsbRuleLayer.fromKey(null));
    }

    @Test
    public void aResultMayNameTheLayerByKeyOrByNumber() {
        assertEquals(UsbRuleLayer.PORT, UsbRuleLayer.fromValue("port"));
        assertEquals(UsbRuleLayer.PORT, UsbRuleLayer.fromValue("PORT"));
        assertEquals(UsbRuleLayer.PORT, UsbRuleLayer.fromValue(2));
        assertEquals(UsbRuleLayer.PORT, UsbRuleLayer.fromValue("2"));
        assertEquals(UsbRuleLayer.ANY, UsbRuleLayer.fromValue(4L));
        assertNull(UsbRuleLayer.fromValue(0));
        assertNull(UsbRuleLayer.fromValue(5));
        assertNull(UsbRuleLayer.fromValue("five"));
        assertNull(UsbRuleLayer.fromValue(null));
    }

    /**
     * Only the catch-all layer needs no subject picked before a target: it matches everything,
     * so there is nothing to ask about. Every layer takes every target, that one included.
     */
    @Test
    public void onlyTheCatchAllLayerNeedsNoSubject() {
        assertTrue(UsbRuleLayer.EXACT.needsSubject());
        assertTrue(UsbRuleLayer.PORT.needsSubject());
        assertTrue(UsbRuleLayer.DEVICE.needsSubject());
        assertFalse(UsbRuleLayer.ANY.needsSubject());
    }

    @Test
    public void eachLayerCarriesTheFieldsItsRulesNeed() {
        assertTrue(UsbRuleLayer.EXACT.hasId);
        assertTrue(UsbRuleLayer.EXACT.hasPort);
        assertFalse(UsbRuleLayer.PORT.hasId);
        assertTrue(UsbRuleLayer.PORT.hasPort);
        assertTrue(UsbRuleLayer.DEVICE.hasId);
        assertFalse(UsbRuleLayer.DEVICE.hasPort);
        assertFalse(UsbRuleLayer.ANY.needsSubject());
    }

    @Test
    public void idIsVidPidWithTheSerialOnlyWhenThereIsOne() {
        assertEquals("090c:1000:0123456789ABCDEF",
            UsbHostDeviceInfo.deriveId("090c", "1000", "0123456789ABCDEF"));
        assertEquals("0bda:8153", UsbHostDeviceInfo.deriveId("0bda", "8153", ""));
    }

    @Test
    public void portDropsTheBusSoBothSpeedsMeetOnOnePhysicalPort() {
        // A USB2 device enumerates on bus 1, a USB3 one on bus 2; same socket, same port.
        assertEquals("1.2.2", UsbHostDeviceInfo.derivePort("1-1.2.2"));
        assertEquals("1.2.2", UsbHostDeviceInfo.derivePort("2-1.2.2"));
        assertEquals("1.4", UsbHostDeviceInfo.derivePort("2-1.4"));
        assertEquals("usb1", UsbHostDeviceInfo.derivePort("usb1"));
    }
}
