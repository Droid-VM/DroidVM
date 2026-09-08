// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The generation a card's title says, from what sysfs reports the device came up at. */
public final class UsbSpeedTest {
    @Test
    public void everyRateAboveFiveGigabitsReadsAsUsbThree() {
        assertEquals("3.0", UsbSpeed.generationOf("5000"));
        assertEquals("3.0", UsbSpeed.generationOf("10000"));
        assertEquals("3.0", UsbSpeed.generationOf("20000"));
    }

    @Test
    public void theSlowerBusesKeepTheirOwnNumbers() {
        assertEquals("2.0", UsbSpeed.generationOf("480"));
        assertEquals("1.1", UsbSpeed.generationOf("12"));
        assertEquals("1.0", UsbSpeed.generationOf("1.5"));
    }

    @Test
    public void aRateThisBuildHasNoWordForSaysNothing() {
        assertEquals("", UsbSpeed.generationOf("40000"));
        assertEquals("", UsbSpeed.generationOf("fast"));
        assertEquals("", UsbSpeed.generationOf(""));
        assertEquals("", UsbSpeed.generationOf(null));
    }

    @Test
    public void whitespaceRoundTheValueIsNotAnUnknownRate() {
        // sysfs attributes come back with the trailing newline the kernel wrote.
        assertEquals("2.0", UsbSpeed.generationOf("480\n"));
        assertEquals("3.0", UsbSpeed.generationOf(" 5000 "));
    }
}
