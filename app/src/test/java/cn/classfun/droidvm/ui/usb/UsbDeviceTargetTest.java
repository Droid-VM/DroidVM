// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.daemon.usb.UsbRules;

/**
 * Where a rule row sends its device, as the page reads and writes it. The row itself is org.json
 * and stays out of here; this is the reading of the three fields it carries.
 */
public final class UsbDeviceTargetTest {
    @Test
    public void aRowWithNoTargetMeansWhatItAlwaysMeant() {
        // The dev phone's rules file: rows written before there was a target to write.
        var host = UsbDeviceTarget.of(null, null, null);
        assertEquals(UsbRules.Target.HOST, host.kind);
        assertNull(host.vmId);
        var vm = UsbDeviceTarget.of(null, "vm-1", "xhci-0");
        assertEquals(UsbRules.Target.VM, vm.kind);
        assertEquals("vm-1", vm.vmId);
        assertEquals("xhci-0", vm.controller);
    }

    @Test
    public void aTargetKeyIsAuthoritativeOverTheVmField() {
        assertEquals(UsbRules.Target.SINK, UsbDeviceTarget.of("sink", null, null).kind);
        assertEquals(UsbRules.Target.HOST, UsbDeviceTarget.of("host", null, null).kind);
        assertEquals(UsbRules.Target.VM, UsbDeviceTarget.of("vm", "vm-1", null).kind);
    }

    @Test
    public void aTargetThatIsNotAVmCarriesNoVm() {
        var sink = UsbDeviceTarget.of("sink", "vm-1", "xhci-0");
        assertNull(sink.vmId);
        assertNull(sink.controller);
    }

    @Test
    public void aTokenThisBuildHasNoWordForFallsBackToTheDerivation() {
        // Not repaired here: the row goes back to the daemon as it came, and is refused by name.
        assertEquals(UsbRules.Target.HOST, UsbDeviceTarget.of("hide", null, null).kind);
        assertEquals(UsbRules.Target.VM, UsbDeviceTarget.of("hide", "vm-1", null).kind);
    }

    @Test
    public void twoTargetsAreTheSameOnlyWhenEveryFieldIs() {
        assertTrue(UsbDeviceTarget.host().sameAs(UsbDeviceTarget.host()));
        assertTrue(UsbDeviceTarget.sink().sameAs(UsbDeviceTarget.sink()));
        assertFalse(UsbDeviceTarget.host().sameAs(UsbDeviceTarget.sink()));
        assertFalse(UsbDeviceTarget.host().sameAs(null));
        assertTrue(UsbDeviceTarget.vm("vm-1", "xhci-0")
            .sameAs(UsbDeviceTarget.vm("vm-1", "xhci-0")));
        assertFalse(UsbDeviceTarget.vm("vm-1", "xhci-0")
            .sameAs(UsbDeviceTarget.vm("vm-2", "xhci-0")));
        // "the VM's first controller" is not the same answer as naming that controller.
        assertFalse(UsbDeviceTarget.vm("vm-1", null)
            .sameAs(UsbDeviceTarget.vm("vm-1", "xhci-0")));
    }

    @Test
    public void theRuleKeyIsTheDaemonsOwn() {
        assertEquals("host", UsbDeviceTarget.host().toRuleTarget());
        assertEquals("sink", UsbDeviceTarget.sink().toRuleTarget());
        assertEquals("vm", UsbDeviceTarget.vm("vm-1", null).toRuleTarget());
    }
}
