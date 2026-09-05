// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.hugepage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.GuestPoolSizing;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;

/**
 * The reserve is budgeted for exactly what the command builder passes: a 5 GB
 * pseudo-unprotected VM with a 1 GB guest pool needs 5 GB, because that mode gets no pool.
 */
public final class PoolPreflightTest {
    private static DataItem vm(String protectedVm, String backend, String mode, boolean gpu) {
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        item.set("memory_mb", 5120L);
        item.set("protected_vm", protectedVm);
        item.set("gpu_backend", backend);
        item.set("gpu_mode", mode);
        item.set("gpu_guest_pool_mb", 1024L);
        var gpu0 = VMScreenConfig.find(item, VMScreenConfig.ID_GPU0);
        if (gpu0 != null) gpu0.setEnabled(gpu);
        return item;
    }

    private static long neededMb(DataItem item) {
        return PoolPreflight.neededPages(item) * PoolPreflight.PAGE_MB;
    }

    @Test
    public void pseudoUnprotectedGetsNoGuestPool() {
        var item = vm("pseudo_unprotected", "gpu_virglrenderer", "native", true);
        assertEquals(0, GuestPoolSizing.bootGuestPoolMb(item));
        assertEquals(5120, neededMb(item));
    }

    @Test
    public void unprotectedGetsNoGuestPoolEither() {
        assertEquals(5120, neededMb(vm("protected_normal", "gpu_virglrenderer", "native", true)));
    }

    @Test
    public void protectedVmPaysForTheGuestPool() {
        var item = vm("protected_without_firmware", "gpu_virglrenderer", "native", true);
        assertEquals(1024, GuestPoolSizing.bootGuestPoolMb(item));
        assertEquals(6144, neededMb(item));
        assertEquals(6144, neededMb(vm("protected_protected", "gpu_virglrenderer", "vulkan", true)));
    }

    @Test
    public void gfxstreamNeedsUdmabufForAPool() {
        var item = vm("protected_without_firmware", "gpu_gfxstream", "vulkan", true);
        assertEquals("udmabuf defaults on", 6144, neededMb(item));
        item.set("gpu_udmabuf", false);
        assertEquals(5120, neededMb(item));
    }

    @Test
    public void noGpuDeviceMeansNoPool() {
        assertEquals(5120, neededMb(vm("protected_without_firmware", "gpu_virglrenderer", "native", false)));
    }

    @Test
    public void onlyThePreallocatedPartIsBudgeted() {
        var item = vm("protected_without_firmware", "gpu_virglrenderer", "native", true);
        item.set("gpu_guest_prealloc_mb", 512L);
        assertEquals(512, GuestPoolSizing.bootGuestPreallocMb(item));
        assertEquals(5632, neededMb(item));
    }

    /**
     * The reserve is Gunyah's, and asking about it anywhere else is a delay with no failure behind
     * it. Every case below names its hypervisor rather than leaving it to be resolved, because
     * resolving one reads the device nodes and the answer would then be this build machine's.
     */
    private static DataItem on(String backend, String hypervisor) {
        var item = vm("protected_without_firmware", "gpu_virglrenderer", "native", true);
        item.set("backend", backend);
        item.set("hypervisor", hypervisor);
        return item;
    }

    @Test
    public void aGunyahVmDrawsOnTheReserve() {
        assertTrue(PoolPreflight.appliesTo(on("crosvm", "gunyah")));
    }

    @Test
    public void theBackendIsNotWhatDecidesIt() {
        // QEMU runs on Gunyah too, and its guest RAM is lent the same way.
        assertTrue(PoolPreflight.appliesTo(on("qemu", "gunyah")));
    }

    @Test
    public void everyOtherHypervisorIsNoneOfItsBusiness() {
        // KVM and GenieZone hand no memory away, and a TCG guest is ordinary process memory. A
        // prompt or a ten-second wait for a pool none of them touch is the bug this gate closes.
        assertFalse(PoolPreflight.appliesTo(on("crosvm", "kvm")));
        assertFalse(PoolPreflight.appliesTo(on("crosvm", "geniezone")));
        assertFalse(PoolPreflight.appliesTo(on("qemu", "soft")));
    }

    @Test
    public void aVmThatDoesNotApplyIsAlwaysEnough() {
        // check() short-circuits before reading sysfs, so this holds on a phone whose reserve is
        // loaded and empty as much as on a machine that has never heard of the module.
        var status = PoolPreflight.check(on("qemu", "soft"));
        assertFalse(status.applicable);
        assertTrue(status.isEnough());
    }
}
