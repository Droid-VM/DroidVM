// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import cn.classfun.droidvm.lib.data.HostAudioDevices;
import cn.classfun.droidvm.lib.store.base.DataItem;

public final class VMCreationDefaultsTest {
    @Test
    public void requestedCreateVmDefaultsAreStable() {
        assertEquals(60, VMScreenConfig.NEW_VM_DEFAULT_POLL_HZ);

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

    @Test
    public void legacyGunyahFolioThresholdMovesToGfxstream() {
        var legacy = DataItem.newObject();
        legacy.set("gunyah_dynamic_share", true);
        legacy.set("gunyah_hugepage_threshold_kb", 2048L);

        VMConfig.migrateLegacySettings(legacy);

        assertEquals(2048L, legacy.optLong("gpu_vram_folio_threshold_kb", -1));
        assertNull(legacy.opt("gunyah_dynamic_share", null));
        assertNull(legacy.opt("gunyah_hugepage_threshold_kb", null));
    }
}
