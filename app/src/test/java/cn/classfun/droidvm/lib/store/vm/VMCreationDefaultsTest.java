// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import cn.classfun.droidvm.lib.data.HostAudioDevices;

public final class VMCreationDefaultsTest {
    @Test
    public void requestedCreateVmDefaultsAreStable() {
        assertEquals(60, VMScreenConfig.NEW_VM_DEFAULT_POLL_HZ);
        assertEquals("127.0.0.1", VMScreenConfig.NEW_VM_DEFAULT_VNC_HOST);
        assertEquals(5900, VMScreenConfig.newVmDefaultVncPort(VMScreenConfig.ID_GPU0));
        assertEquals(5909, VMScreenConfig.newVmDefaultVncPort(VMScreenConfig.ID_SIMPLEFB));

        var sound = VMPeripheralConfig.createDefaultVirtioSound();
        assertEquals(PeripheralType.VIRTIO_SOUND, sound.getType());
        var endpoints = sound.getEndpoints();
        assertEquals(2, endpoints.size());
        assertEquals(SoundMode.SPEAKER, endpoints.get(0).getMode());
        assertEquals(HostAudioDevices.SYSTEM_DEFAULT_KEY,
            endpoints.get(0).getHostDevice());
        assertEquals(SoundMode.MICROPHONE, endpoints.get(1).getMode());
        assertEquals(HostAudioDevices.SYSTEM_DEFAULT_KEY,
            endpoints.get(1).getHostDevice());
    }
    /**
     * What Customize opens with, against what the editor would fall back to on its own.
     *
     * <p>Every field the editor shows has up to three defaults behind it: the value this factory
     * writes into the config, the fallback the editor passes to {@code optLong} when the config
     * does not mention the key, and the {@code android:text} in the layout. They are read in that
     * order, so the first one present wins and the ones below it are never seen. That is fine
     * while they agree and invisible when they do not -- the config said the virtio-gpu screen was
     * bound to nothing while the editor's own row said NATIVE, and because the config is loaded
     * over the row, turning the device on showed "sink" with no way to tell which layer said so.
     *
     * <p>So: the screens' exporter comes from one shared constant, and the graphics pools are the
     * editor's to default. A pool key appearing here means this test has to say what the editor
     * should show for it -- which is the point, because that is the moment the two can disagree.
     */
    @Test
    public void customizeDefaultsAgreeWithTheEditorsOwn() {
        // Only LendMthpMode.defaultForDevice reads it, inside its own try/catch.
        var item = VMConfig.createWithCustomizeDefaults(null).item;

        var gpu = VMScreenConfig.find(item, VMScreenConfig.ID_GPU0);
        assertNotNull(gpu);
        // Off: a new VM has no virtio-gpu device until asked. Bound anyway, because this is what
        // the editor shows the moment it is asked, and save() writes NONE while it stays off.
        assertFalse(gpu.isEnabled());
        assertEquals(VMScreenConfig.NEW_VM_DEFAULT_EXPORTER, gpu.getExporter());
        // The ladder belongs to the edge, so the stored ceiling has to be the one computed for the
        // exporter stored beside it. These two came apart once -- the cap was worked out for
        // NATIVE while the exporter said NONE -- and nothing downstream could notice, because each
        // is separately valid. Which rung that is per exporter is DisplayTransportCapTest's
        // subject, including that switching to VNC moves it to the hardware encoder.
        assertEquals(DisplayTransportCap.defaultFor(
                VMScreenConfig.ID_GPU0, VMScreenConfig.NEW_VM_DEFAULT_EXPORTER),
            gpu.getTransportCap());
        // Written even while this screen is off and bound to something that is not VNC, for the
        // same reason the exporter above it is: the editor loads this config over its rows, so
        // this is what it shows the moment the user switches the screen to VNC. The two screens'
        // ports differ because both can be exported at once and two servers may not share one.
        assertEquals(VMScreenConfig.NEW_VM_DEFAULT_VNC_HOST, gpu.getVncHost());
        assertEquals(VMScreenConfig.NEW_VM_DEFAULT_VNC_PORT_GPU0, gpu.getVncPort());

        var fb = VMScreenConfig.find(item, VMScreenConfig.ID_SIMPLEFB);
        assertNotNull(fb);
        assertTrue(fb.isEnabled());
        assertEquals(VMScreenConfig.NEW_VM_DEFAULT_EXPORTER, fb.getExporter());
        assertEquals(DisplayTransportCap.defaultFor(
                VMScreenConfig.ID_SIMPLEFB, VMScreenConfig.NEW_VM_DEFAULT_EXPORTER),
            fb.getTransportCap());
        assertEquals(VMScreenConfig.NEW_VM_DEFAULT_VNC_HOST, fb.getVncHost());
        assertEquals(VMScreenConfig.NEW_VM_DEFAULT_VNC_PORT_SIMPLEFB, fb.getVncPort());

        // The graphics pools: the editor owns these defaults (VMEditGraphicsTab.loadConfigLocked
        // passes each one to optLong), and the layout repeats them. Writing one here would take
        // that decision away silently, so if this list has to change, change the editor with it.
        for (var key : new String[] {
            "gpu_drm2kgsl_pool_mb", "gpu_host_pool_mb", "gpu_venus_pool_mb",
            "gpu_guest_pool_mb", "gpu_guest_prealloc_mb", "gpu_guest_step_mb",
            "gpu_guest_max_grants", "gpu_pool_blob_max_kb", "gpu_vram_quota_mb",
        }) {
            assertEquals(fmt("%s is the editor's default to make, not this factory's", key),
                -1L, item.optLong(key, -1L));
        }
    }
}
