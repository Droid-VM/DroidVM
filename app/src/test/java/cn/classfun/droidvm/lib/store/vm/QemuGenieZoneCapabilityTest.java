// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QemuGenieZoneCapabilityTest {
    @Test
    public void qemuAndCrosvmBothRecognizeGenieZone() {
        assertTrue(VMHypervisor.isBackendSupported(VMBackend.QEMU, VMHypervisor.GENIEZONE));
        assertTrue(VMHypervisor.isBackendSupported(VMBackend.CROSVM, VMHypervisor.GENIEZONE));
    }

    @Test
    public void acceleratorHelpMustAdvertiseGzvm() {
        assertTrue(QemuGenieZoneCapability.acceleratorIsAvailable(
            "Accelerators supported in QEMU binary:\ngzvm\nkvm\ntcg"));
        assertFalse(QemuGenieZoneCapability.acceleratorIsAvailable(
            "Accelerators supported in QEMU binary:\ngunyah\nkvm\ntcg"));
        assertFalse(QemuGenieZoneCapability.acceleratorIsAvailable(""));
    }

    @Test
    public void preflightRequiresNodeAndAccelerator() {
        assertTrue(QemuGenieZoneCapability.preflight(true, true));
        assertFalse(QemuGenieZoneCapability.preflight(false, true));
        assertFalse(QemuGenieZoneCapability.preflight(true, false));
        assertFalse(QemuGenieZoneCapability.preflight(false, false));
    }
}
