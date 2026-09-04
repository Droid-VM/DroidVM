// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

import cn.classfun.droidvm.lib.hugepage.PoolPreflight;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
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

    /** Adds a camera row, the way the peripheral editor's picker would. */
    private static DataItem withCamera(DataItem item, String hostDevice, String label) {
        var peripherals = item.opt("peripherals", DataItem.newArray());
        var camera = new VMPeripheralConfig(DataItem.newObject());
        camera.setType(PeripheralType.VIRTIO_CAMERA);
        camera.setHostDevice(hostDevice, label);
        peripherals.append(camera.item);
        item.set("peripherals", peripherals);
        return item;
    }

    private static String mediaFragment(DataItem item, ProtectedVM pvm) {
        var preAlloc = new StringBuilder();
        CrosvmBackendInstance.appendMediaPoolOptions(preAlloc, item, pvm);
        return record(preAlloc.toString());
    }

    /**
     * Every non-empty {@code --pre-alloc} this file asserts, collected into
     * {@code build/vpu-a2/prealloc.txt} for the scratch harness in {@code logs/vpu_wp/scratch-a2}
     * to hand to crosvm's own {@code PreAllocConfig}. It is {@code deny_unknown_fields}: a key
     * name that drifted is a VM that will not start, and only the other side can say so.
     */
    private static final Set<String> EMITTED = new LinkedHashSet<>();

    private static synchronized String record(String preAlloc) {
        if (!preAlloc.isEmpty()) EMITTED.add(preAlloc);
        return preAlloc;
    }

    @AfterClass
    public static void writeEmitted() throws IOException {
        var dir = new File("build/vpu-a2");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        var text = new StringBuilder();
        for (var line : EMITTED) text.append(line).append('\n');
        Files.write(new File(dir, "prealloc.txt").toPath(),
            text.toString().getBytes(StandardCharsets.UTF_8));
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
            record(preAlloc.toString()));
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

    /**
     * A camera is a device, and on Gunyah crosvm refuses a virtio-media device with no
     * media_host pool -- so the pool follows the device even with the VPU switch off. The guest
     * pool does not: it is the other direction of data and nothing asks for it.
     */
    @Test
    public void aCameraForcesTheHostPoolWithTheSwitchOff() {
        var item = withCamera(vm("protected_without_firmware", false), "0", "Back camera (0)");
        assertEquals("media-host-mb=256",
            mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        assertEquals(256, VpuConfig.effectiveHostPoolMb(item));
        assertTrue(VpuConfig.hostPoolIsForcedByDevice(item));
        // ... and it stays out of the huge-page reserve: media_host is consume_system_mem.
        assertEquals(0, VpuConfig.bootMediaGuestMb(item));
        assertEquals(4096, PoolPreflight.neededPages(item) * PoolPreflight.PAGE_MB);
    }

    /** A stored zero is not a choice made for the camera; a device with no pool cannot start. */
    @Test
    public void aForcedHostPoolFallsBackToTheDefaultSize() {
        var item = withCamera(vm("protected_without_firmware", false), "", "");
        VpuConfig.setHostPoolMb(item, 0);
        assertEquals("media-host-mb=256",
            mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
    }

    /** Switch on and a camera present: the switch's own two pools, once each. */
    @Test
    public void theSwitchOnWithACameraIsStillOneOfEachPool() {
        var item = withCamera(vm("protected_without_firmware", true), "1", "Front camera (1)");
        assertEquals("media-host-mb=256,media-guest-mb=128",
            mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        assertFalse(VpuConfig.hostPoolIsForcedByDevice(item));
        assertEquals(4096 + 128, PoolPreflight.neededPages(item) * PoolPreflight.PAGE_MB);
    }

    /** A VM with no media device and the switch off asks for nothing, camera row or not. */
    @Test
    public void aSoundOnlyVmIsUntouched() {
        var item = vm("protected_without_firmware", false);
        assertFalse(VpuConfig.hasMediaDevice(item));
        assertEquals(0, VpuConfig.effectiveHostPoolMb(item));
        assertEquals("", mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
    }

    /**
     * The whole media contribution to a Gunyah VM that also has a renderer, in the order
     * buildCommand appends it: the renderer route first, the media pools last, one --pre-alloc.
     */
    @Test
    public void aCameraJoinsTheRendererPreAllocRatherThanMakingASecondOne() {
        var item = withCamera(vm("protected_without_firmware", false), "0", "Back camera (0)");
        var preAlloc = new StringBuilder();
        CrosvmBackendInstance.appendPreAllocKey(preAlloc, "venus-host-mb=256");
        CrosvmBackendInstance.appendPreAllocKey(preAlloc, "gpu-guest-mb=1024");
        CrosvmBackendInstance.appendMediaPoolOptions(preAlloc, item,
            ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        assertEquals("venus-host-mb=256,gpu-guest-mb=1024,media-host-mb=256",
            record(preAlloc.toString()));
    }
}
