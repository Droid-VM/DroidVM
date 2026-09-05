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
 *
 * <p>Both pools come from the VPU switch and from nothing else. A camera row does not buy itself
 * one -- it is the other way round: a camera is a virtio-media device and is only attached when
 * the switch that pays for the transport is on, which is
 * {@link VpuConfig#mediaDevicesAttached}.</p>
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

    /** 0 is not a smaller guest pool, it is no media_guest node at all. */
    @Test
    public void aZeroedGuestPoolIsNoNodeRatherThanAnEmptyOne() {
        var item = vm("protected_without_firmware", true);
        VpuConfig.setGuestPoolMb(item, 0);
        assertEquals("media-host-mb=256",
            mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        assertEquals(4096, PoolPreflight.neededPages(item) * PoolPreflight.PAGE_MB);
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
     * The rule this whole file exists for: a camera row on a VM with the switch off gets
     * nothing. No {@code --virtio-media} device (the backend skips the row --
     * {@code mediaDevicesAttached} is the gate {@code buildPeripheralCommand} reads), and no
     * media pools, so the VM's {@code --pre-alloc} has no media half at all and the huge-page
     * reserve is exactly the RAM.
     */
    @Test
    public void aCameraWithTheSwitchOffGetsNoDeviceAndNoPools() {
        var item = withCamera(vm("protected_without_firmware", false), "0", "Back camera (0)");
        assertFalse(VpuConfig.mediaDevicesAttached(item));
        assertEquals("", mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        assertEquals(0, VpuConfig.hostPoolMbFor(item));
        assertEquals(0, VpuConfig.bootMediaGuestMb(item));
        assertEquals(4096, PoolPreflight.neededPages(item) * PoolPreflight.PAGE_MB);
        // Nor does it join a renderer's pre-alloc: the media half is simply absent.
        var preAlloc = new StringBuilder();
        CrosvmBackendInstance.appendPreAllocKey(preAlloc, "venus-host-mb=256");
        CrosvmBackendInstance.appendMediaPoolOptions(preAlloc, item,
            ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        assertEquals("venus-host-mb=256", preAlloc.toString());
    }

    /**
     * A stored zero cannot be honoured with the switch on: crosvm refuses to create a
     * virtio-media device without a media_host pool on gunyah, so media-host-mb=0 would be a VM
     * that stops booting the moment its camera row becomes a device.
     */
    @Test
    public void aZeroHostPoolWithTheSwitchOnFallsBackToTheDefaultSize() {
        var item = withCamera(vm("protected_without_firmware", true), "", "");
        VpuConfig.setHostPoolMb(item, 0);
        assertTrue(VpuConfig.hostPoolIsDefaulted(item));
        assertEquals(256, VpuConfig.hostPoolMbFor(item));
        assertEquals("media-host-mb=256,media-guest-mb=128",
            mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        // With the switch off the same stored zero is just one more thing that is not passed.
        VpuConfig.setEnabled(item, false);
        assertFalse(VpuConfig.hostPoolIsDefaulted(item));
        assertEquals("", mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
    }

    /**
     * Switch on and a camera present: the switch's own two pools, once each. A device does not
     * add a pool, and it is attached because the switch is on.
     */
    @Test
    public void theSwitchOnWithACameraIsStillOneOfEachPool() {
        var item = withCamera(vm("protected_without_firmware", true), "1", "Front camera (1)");
        assertTrue(VpuConfig.mediaDevicesAttached(item));
        assertEquals("media-host-mb=256,media-guest-mb=128",
            mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        assertFalse(VpuConfig.hostPoolIsDefaulted(item));
        assertEquals(4096 + 128, PoolPreflight.neededPages(item) * PoolPreflight.PAGE_MB);
    }

    /** A VM with the switch off asks for nothing, camera row or not. */
    @Test
    public void aSoundOnlyVmIsUntouched() {
        var item = vm("protected_without_firmware", false);
        assertFalse(VpuConfig.mediaDevicesAttached(item));
        assertEquals(0, VpuConfig.hostPoolMbFor(item));
        assertEquals("", mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
    }

    /**
     * The codec override subtracts devices and nothing else. {@code vpu_codec_enabled=false} is
     * how an acceptance run boots a VPU VM with no decoder helper in it -- and how a phone whose
     * codec store offers no usable hardware decoder boots one at all -- so the pools it is
     * compared against have to be the same ones. Which devices it does take away is
     * {@link CodecDeviceConfigTest}.
     */
    @Test
    public void theCodecOverrideDoesNotChangeThePools() {
        var item = withCamera(vm("protected_without_firmware", true), "0", "Back camera (0)");
        VpuConfig.setCodecEnabled(item, false);
        assertFalse(VpuConfig.codecDevicesAttached(item));
        assertTrue(VpuConfig.mediaDevicesAttached(item));
        assertEquals("media-host-mb=256,media-guest-mb=128",
            mediaFragment(item, ProtectedVM.PROTECTED_WITHOUT_FIRMWARE));
        assertEquals(4096 + 128, PoolPreflight.neededPages(item) * PoolPreflight.PAGE_MB);
    }

    /**
     * The whole media contribution to a Gunyah VM that also has a renderer, in the order
     * buildCommand appends it: the renderer route first, the media pools last, one --pre-alloc.
     */
    @Test
    public void theMediaPoolsJoinTheRendererPreAllocRatherThanMakingASecondOne() {
        var item = withCamera(vm("protected_normal", true), "0", "Back camera (0)");
        var preAlloc = new StringBuilder();
        CrosvmBackendInstance.appendPreAllocKey(preAlloc, "venus-host-mb=256");
        CrosvmBackendInstance.appendPreAllocKey(preAlloc, "gpu-guest-mb=1024");
        CrosvmBackendInstance.appendMediaPoolOptions(preAlloc, item,
            ProtectedVM.PROTECTED_NORMAL);
        assertEquals("venus-host-mb=256,gpu-guest-mb=1024,media-host-mb=256",
            record(preAlloc.toString()));
    }

    /** The same VM in a protected mode, where the guest pool applies as well. */
    @Test
    public void bothPoolsJoinTheRendererPreAlloc() {
        var item = withCamera(vm("protected_without_firmware", true), "0", "Back camera (0)");
        var preAlloc = new StringBuilder();
        CrosvmBackendInstance.appendPreAllocKey(preAlloc, "venus-host-mb=256");
        CrosvmBackendInstance.appendPreAllocKey(preAlloc, "gpu-guest-mb=1024");
        CrosvmBackendInstance.appendMediaPoolOptions(preAlloc, item,
            ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        assertEquals("venus-host-mb=256,gpu-guest-mb=1024,media-host-mb=256,media-guest-mb=128",
            record(preAlloc.toString()));
    }
}
