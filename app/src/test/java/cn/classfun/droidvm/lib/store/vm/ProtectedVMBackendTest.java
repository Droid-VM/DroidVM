package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProtectedVMBackendTest {
    @Test
    public void pseudoUnprotectedRequiresCrosvmGunyah() {
        assertTrue(ProtectedVM.supportsPseudoUnprotected(VMBackend.CROSVM, VMHypervisor.GUNYAH));
        for (var backend : VMBackend.values()) {
            for (var hypervisor : VMHypervisor.values()) {
                if (backend == VMBackend.CROSVM && hypervisor == VMHypervisor.GUNYAH) continue;
                assertFalse(ProtectedVM.supportsPseudoUnprotected(backend, hypervisor));
                assertEquals(ProtectedVM.PROTECTED_NORMAL,
                    ProtectedVM.resolveFor(ProtectedVM.PSEUDO_UNPROTECTED, backend, hypervisor));
            }
        }
    }

    @Test
    public void realProtectedModesArePreserved() {
        for (var mode : ProtectedVM.values()) {
            if (mode == ProtectedVM.PSEUDO_UNPROTECTED) continue;
            assertEquals(mode, ProtectedVM.resolveFor(mode, VMBackend.CROSVM, VMHypervisor.GENIEZONE));
        }
        assertEquals(ProtectedVM.PSEUDO_UNPROTECTED,
            ProtectedVM.defaultFor(VMBackend.CROSVM, VMHypervisor.GUNYAH));
        assertEquals(ProtectedVM.PROTECTED_NORMAL,
            ProtectedVM.defaultFor(VMBackend.CROSVM, VMHypervisor.KVM));
    }
}
