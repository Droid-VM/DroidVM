// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The crosvm usb CLI prints one line; these are the lines it prints. */
public final class CrosvmUsbControlParseTest {
    @Test
    public void attachReturnsThePortItWasGiven() throws Exception {
        assertEquals(3, CrosvmUsbControl.parseAttach("ok 3\n"));
    }

    @Test
    public void attachRefusalBecomesTheToken() {
        var e = assertThrows(UsbControlException.class,
            () -> CrosvmUsbControl.parseAttach("no_available_port"));
        assertEquals("no_available_port", e.token);
    }

    @Test
    public void detachReturnsThePort() throws Exception {
        assertEquals(2, CrosvmUsbControl.parseDetach("ok 2"));
    }

    @Test
    public void listSplitsIntoPortVidPid() throws Exception {
        var entries = CrosvmUsbControl.parseList("devices 1 090c 1000 2 0bda 8153");
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).port);
        assertEquals("090c", entries.get(0).vid);
        assertEquals("1000", entries.get(0).pid);
        assertEquals(2, entries.get(1).port);
        assertEquals("0bda", entries.get(1).vid);
        assertEquals("8153", entries.get(1).pid);
    }

    @Test
    public void anEmptyListIsJustTheKeyword() throws Exception {
        assertTrue(CrosvmUsbControl.parseList("devices").isEmpty());
    }

    @Test
    public void listRefusesAPartialGroup() {
        // Two tokens after the keyword address no device; taking the empty prefix of them would
        // report a VM with nothing attached.
        assertThrows(UsbControlException.class,
            () -> CrosvmUsbControl.parseList("devices 1 090c"));
    }

    @Test
    public void listRefusesAnythingElse() {
        var e = assertThrows(UsbControlException.class,
            () -> CrosvmUsbControl.parseList("garbage"));
        assertEquals("garbage", e.token);
    }
}
