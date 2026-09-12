// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMXhciConfig;

/**
 * The two questions the protected-guest warning rests on: whether this VM's memory is lent at
 * all, and whether a USB rule would actually hand it a device. The rules' JSON edge is not
 * covered -- org.json is a stub under the test android.jar (see {@code UsbRulesTest}) -- so the
 * decision is asked with the fields a rule carries.
 */
public final class ProtectedGuestPreflightTest {
    private static final String VM_ID = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_VM = "99999999-8888-7777-6666-555555555555";

    /** A VM with one xHCI controller, which the rules below point at. */
    private static VMConfig vmWithController() {
        var config = new VMConfig();
        config.setId(VM_ID);
        VMXhciConfig.addController(config.item);
        return config;
    }

    @Test
    public void aRuleForThisVmCounts() {
        assertTrue(ProtectedGuestPreflight.pointsAtVm(
            vmWithController(), VM_ID, "vm", null));
    }

    /** A rule written before targets existed: its VM is the whole of what it said. */
    @Test
    public void aTargetlessRuleWithAVmCounts() {
        assertTrue(ProtectedGuestPreflight.pointsAtVm(
            vmWithController(), VM_ID, null, null));
    }

    @Test
    public void rulesForSomeoneElseDoNot() {
        var config = vmWithController();
        assertFalse(ProtectedGuestPreflight.pointsAtVm(config, OTHER_VM, "vm", null));
        assertFalse(ProtectedGuestPreflight.pointsAtVm(config, null, "host", null));
        assertFalse(ProtectedGuestPreflight.pointsAtVm(config, VM_ID, "sink", null));
    }

    /** Naming the VM's own controller is the same rule, said explicitly. */
    @Test
    public void aRuleNamingThisVmsControllerCounts() {
        var config = vmWithController();
        assertTrue(ProtectedGuestPreflight.pointsAtVm(
            config, VM_ID, "vm", VMXhciConfig.firstControllerId(config.item)));
    }

    /** A controller this VM no longer has: the rule can never attach anything. */
    @Test
    public void aDanglingControllerDoesNot() {
        assertFalse(ProtectedGuestPreflight.pointsAtVm(
            vmWithController(), VM_ID, "vm", "xhci-7"));
    }

    /** No controller at all: nothing to attach to, whatever the rules say. */
    @Test
    public void aVmWithoutAControllerDoesNot() {
        var config = new VMConfig();
        config.setId(VM_ID);
        assertFalse(ProtectedGuestPreflight.pointsAtVm(config, VM_ID, "vm", null));
    }

    /** Which modes lend the guest's memory -- the property both warnings follow from. */
    @Test
    public void pseudoUnprotectedDoesNotLendMemory() {
        var config = new VMConfig();
        for (var mode : new ProtectedVM[]{
            ProtectedVM.PROTECTED_PROTECTED, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE}) {
            config.item.set(ProtectedVM.KEY, mode);
            assertTrue(mode.name(), ProtectedVM.lendsGuestMemory(config.item));
        }
        for (var mode : new ProtectedVM[]{
            ProtectedVM.PROTECTED_NORMAL, ProtectedVM.PSEUDO_UNPROTECTED}) {
            config.item.set(ProtectedVM.KEY, mode);
            assertFalse(mode.name(), ProtectedVM.lendsGuestMemory(config.item));
        }
    }
}
