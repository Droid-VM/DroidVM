// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import cn.classfun.droidvm.lib.hugepage.PoolPreflight;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VpuConfig;

/**
 * What the media half of {@code --pre-alloc} says, per protection mode.
 *
 * <p>The pools are only real memory when the guest pool is one: media_host is
 * {@code consume_system_mem} and comes out of {@code --mem}, media_guest is taken beside it. The
 * two facts that have to stay together are the argument the daemon passes and the reserve the
 * preflight asks for, which is why both are asserted here off the same config.</p>
 */
public final class CrosvmMediaPoolTest {
    private static DataItem vm(String protectedVm, boolean vpu) {
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        item.set("memory_mb", 4096L);
        item.set("protected_vm", protectedVm);
        VpuConfig.setEnabled(item, vpu);
        VpuConfig.setHostPoolMb(item, VpuConfig.DEFAULT_HOST_POOL_MB);
        VpuConfig.setGuestPoolMb(item, VpuConfig.DEFAULT_GUEST_POOL_MB);
        return item;
    }

    private static String mediaFragment(DataItem item, ProtectedVM pvm) {
        var preAlloc = new StringBuilder();
        CrosvmBackendInstance.appendMediaPoolOptions(preAlloc, item, pvm);
        return preAlloc.toString();
    }

    @Test
    public void protectedModesGetBothPools() {
        assertEquals("media-host-mb=256,media-guest-mb=128",
            mediaFragment(vm("protected_without_firmware", true),
                ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        assertEquals("media-host-mb=256,media-guest-mb=128",
            mediaFragment(vm("protected_protected", true), ProtectedVM.PROTECTED_PROTECTED));
    }

    @Test
    public void hostVisibleRamGetsTheHostPoolOnly() {
        assertEquals("media-host-mb=256",
            mediaFragment(vm("pseudo_unprotected", true), ProtectedVM.PSEUDO_UNPROTECTED));
        assertEquals("media-host-mb=256",
            mediaFragment(vm("protected_normal", true), ProtectedVM.PROTECTED_NORMAL));
    }

    @Test
    public void theSwitchOffMeansNoPoolsAtAll() {
        assertEquals("", mediaFragment(vm("protected_without_firmware", false),
            ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        assertEquals(0, VpuConfig.bootMediaGuestMb(vm("protected_without_firmware", false)));
    }

    @Test
    public void aZeroedPoolIsNoNodeRatherThanAnEmptyOne() {
        var item = vm("protected_without_firmware", true);
        VpuConfig.setGuestPoolMb(item, 0);
        assertEquals("media-host-mb=256",
            mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        VpuConfig.setHostPoolMb(item, 0);
        assertEquals("", mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
    }

    @Test
    public void mediaFragmentJoinsTheRendererOne() {
        var item = vm("protected_without_firmware", true);
        var preAlloc = new StringBuilder();
        CrosvmBackendInstance.appendPreAllocKey(preAlloc, "venus-host-mb=256");
        CrosvmBackendInstance.appendMediaPoolOptions(preAlloc, item,
            ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        assertEquals("venus-host-mb=256,media-host-mb=256,media-guest-mb=128",
            preAlloc.toString());
    }

    /** The reserve pays for media_guest and not for media_host. */
    @Test
    public void preflightBudgetsTheGuestPoolOnly() {
        var on = vm("protected_without_firmware", true);
        var off = vm("protected_without_firmware", false);
        assertEquals(128, VpuConfig.bootMediaGuestMb(on));
        assertEquals(4096 + 128,
            PoolPreflight.neededPages(on) * PoolPreflight.PAGE_MB);
        assertEquals(4096, PoolPreflight.neededPages(off) * PoolPreflight.PAGE_MB);
        assertEquals(4096, PoolPreflight.neededPages(vm("pseudo_unprotected", true))
            * PoolPreflight.PAGE_MB);
    }
}
