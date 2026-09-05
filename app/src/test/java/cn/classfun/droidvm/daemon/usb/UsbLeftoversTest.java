// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * The record of interfaces a restore pass could not give back, fed the outcomes by hand: what
 * a VM's exit is owed, what a scan makes of them, and what an unplug forgets. No sysfs -- the
 * waiting and the probing are the manager's and are not exercised here.
 */
public final class UsbLeftoversTest {
    private static final String VM_A = "0f4c9e2a-0000-4000-8000-00000000000a";
    private static final String VM_B = "0f4c9e2a-0000-4000-8000-00000000000b";

    /** The audio device of the on-device run, left whole by VM A. */
    private static UsbLeftovers audioLeftByA() {
        var leftovers = new UsbLeftovers();
        for (var i = 0; i < 4; i++) leftovers.leave("1-1.2.2:1." + i, VM_A);
        return leftovers;
    }

    @Test
    public void startsEmpty() {
        var leftovers = new UsbLeftovers();
        assertTrue(leftovers.isEmpty());
        assertEquals(0, leftovers.size());
        assertTrue(leftovers.leftBy(VM_A).isEmpty());
        assertTrue(leftovers.take(List.of("1-1.2.2:1.0")).isEmpty());
    }

    @Test
    public void aVmIsOwedWhatItLeftAndNothingElse() {
        var leftovers = audioLeftByA();
        leftovers.leave("1-1.3:1.0", VM_B);
        assertEquals(5, leftovers.size());
        assertEquals(List.of("1-1.2.2:1.0", "1-1.2.2:1.1", "1-1.2.2:1.2", "1-1.2.2:1.3"),
            leftovers.leftBy(VM_A));
        assertEquals(List.of("1-1.3:1.0"), leftovers.leftBy(VM_B));
        // A query, not a take: the exit that acts on it takes afterwards.
        assertEquals(5, leftovers.size());
    }

    @Test
    public void aTakeRemovesTheInterfacesAndNamesTheirVm() {
        var leftovers = audioLeftByA();
        leftovers.leave("1-1.3:1.0", VM_B);
        var taken = leftovers.take(List.of("1-1.2.2:1.0", "1-1.3:1.0", "1-1.6:1.0"));
        // The unrecorded one is not in the answer; there is nothing to say about it.
        assertEquals(Map.of("1-1.2.2:1.0", VM_A, "1-1.3:1.0", VM_B), taken);
        assertEquals(3, leftovers.size());
        assertFalse(leftovers.contains("1-1.2.2:1.0"));
        assertTrue(leftovers.contains("1-1.2.2:1.1"));
        // Taken is taken: a second trigger for the same interface finds nothing to do.
        assertTrue(leftovers.take(List.of("1-1.2.2:1.0")).isEmpty());
    }

    @Test
    public void leavingAgainReassignsTheInterface() {
        var leftovers = new UsbLeftovers();
        leftovers.leave("1-1.2.2:1.0", VM_A);
        leftovers.leave("1-1.2.2:1.0", VM_B);
        assertEquals(1, leftovers.size());
        assertTrue(leftovers.leftBy(VM_A).isEmpty());
        assertEquals(List.of("1-1.2.2:1.0"), leftovers.leftBy(VM_B));
    }

    @Test
    public void aScanSortsTheRecordByWhatHoldsEachInterface() {
        var leftovers = audioLeftByA();
        var scan = leftovers.scan(Map.of(
            "1-1.2.2:1.0", "",              // free: the claim went
            "1-1.2.2:1.1", "usbfs",         // still the VMM's
            "1-1.2.2:1.2", "snd-usb-audio", // the host got it back some other way
            // 1-1.2.2:1.3 was not seen at all
            "1-1.3:1.0", ""                 // free, but never ours
        ));
        assertEquals(List.of("1-1.2.2:1.0"), scan.free);
        assertEquals(Map.of("1-1.2.2:1.2", "snd-usb-audio"), scan.reclaimed);
        // The free one stays until it is taken; the reclaimed one is gone for good; the
        // claimed and the unseen ones wait for another scan or the unplug.
        assertTrue(leftovers.contains("1-1.2.2:1.0"));
        assertTrue(leftovers.contains("1-1.2.2:1.1"));
        assertFalse(leftovers.contains("1-1.2.2:1.2"));
        assertTrue(leftovers.contains("1-1.2.2:1.3"));
        assertEquals(3, leftovers.size());
    }

    @Test
    public void aScanWithNothingFreeChangesNothing() {
        var leftovers = audioLeftByA();
        var scan = leftovers.scan(Map.of("1-1.2.2:1.0", "usbfs", "1-1.2.2:1.1", "usbfs"));
        assertTrue(scan.free.isEmpty());
        assertTrue(scan.reclaimed.isEmpty());
        assertEquals(4, leftovers.size());
    }

    @Test
    public void anUnplugForgetsThatDeviceOnly() {
        var leftovers = audioLeftByA();
        leftovers.leave("1-1.3:1.0", VM_A);
        // A name that only starts the same is a different device.
        leftovers.leave("1-1.2.22:1.0", VM_A);
        var dropped = leftovers.forgetDevice("1-1.2.2");
        assertEquals(List.of("1-1.2.2:1.0", "1-1.2.2:1.1", "1-1.2.2:1.2", "1-1.2.2:1.3"),
            dropped);
        assertEquals(List.of("1-1.3:1.0", "1-1.2.22:1.0"), leftovers.leftBy(VM_A));
        assertTrue(leftovers.forgetDevice("1-1.6").isEmpty());
    }

    @Test
    public void anInterfaceNamesItsDevice() {
        assertEquals("1-1.2.2", UsbLeftovers.deviceOf("1-1.2.2:1.0"));
        assertEquals("2-1.4", UsbLeftovers.deviceOf("2-1.4:2.11"));
        assertEquals("1-1", UsbLeftovers.deviceOf("1-1"));
    }
}
