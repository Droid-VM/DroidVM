// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.lib.data.HostAudioDevices;

public final class VMCreationDefaultsTest {
    @Test
    public void requestedCreateVmDefaultsAreStable() {
        assertTrue(VMConfig.NEW_VM_DEFAULT_GUNYAH_DYNAMIC_SHARE);
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
}
