// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static cn.classfun.droidvm.ui.vm.KernelModulePreflight.GH_UNMOVABLE;
import static cn.classfun.droidvm.ui.vm.KernelModulePreflight.GUNYAH_HOST_SHARE;
import static cn.classfun.droidvm.ui.vm.KernelModulePreflight.GUNYAH_KVCALLOC;
import static cn.classfun.droidvm.ui.vm.KernelModulePreflight.KVCALLOC_MEMORY_MB;
import static cn.classfun.droidvm.ui.vm.KernelModulePreflight.UDMABUF;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;

/** Which modules a configuration asks for, before any of them is looked up on the device. */
public final class KernelModulePreflightTest {
    private static DataItem vm(ProtectedVM mode, boolean gpu, long memoryMb) {
        var item = DataItem.newObject();
        item.set("protected_vm", mode);
        item.set("memory_mb", memoryMb);
        VMScreenConfig.of(item, VMScreenConfig.ID_GPU0).setEnabled(gpu);
        return item;
    }

    private static List<String> wanted(DataItem item) {
        return new ArrayList<>(KernelModulePreflight.wantedBy(item).keySet());
    }

    @Test
    public void plainProtectedVmWantsNothing() {
        assertTrue(wanted(vm(ProtectedVM.PROTECTED_WITHOUT_FIRMWARE, false, 1024)).isEmpty());
    }

    @Test
    public void pseudoUnprotectedWantsTheShareModule() {
        assertEquals(List.of(GUNYAH_HOST_SHARE),
            wanted(vm(ProtectedVM.PSEUDO_UNPROTECTED, false, 1024)));
    }

    @Test
    public void gpuWantsThePinAndTheDmaBufModules() {
        assertEquals(List.of(GH_UNMOVABLE, UDMABUF),
            wanted(vm(ProtectedVM.PROTECTED_PROTECTED, true, 1024)));
    }

    /**
     * Whether this phone is one the kvcalloc fix was built for is the module list's answer, not
     * this rule's - here only the size matters.
     */
    @Test
    public void kvcallocWantedOnlyOverTheMemoryThreshold() {
        assertEquals(List.of(GUNYAH_KVCALLOC),
            wanted(vm(ProtectedVM.PROTECTED_PROTECTED, false, KVCALLOC_MEMORY_MB + 1)));
        assertTrue("at the threshold the contiguous allocation still comes through",
            wanted(vm(ProtectedVM.PROTECTED_PROTECTED, false, KVCALLOC_MEMORY_MB)).isEmpty());
    }

    /** Everything at once, in the order the dialog lists them. */
    @Test
    public void everyRuleCanFireTogether() {
        assertEquals(List.of(GUNYAH_HOST_SHARE, GH_UNMOVABLE, UDMABUF, GUNYAH_KVCALLOC),
            wanted(vm(ProtectedVM.PSEUDO_UNPROTECTED, true, 8192)));
    }
}
