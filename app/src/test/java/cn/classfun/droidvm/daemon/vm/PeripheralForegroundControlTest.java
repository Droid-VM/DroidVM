// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VpuConfig;

/**
 * The foreground-service rule: any VM that is not STOPPED and carries a device needing a service
 * type keeps that type up, and the daemon runs the union over every VM.
 *
 * <p>{@code refresh} itself walks a {@code VMInstanceStore} and talks to the platform, so what is
 * tested is the rule it folds -- {@code typesFor(state, item)}, which is where every decision
 * actually is. The store walk around it is a fold of exactly this over every instance.</p>
 *
 * <p>"Carries a device" means the device the backend will really attach, not the row: a camera on
 * a VM whose VPU switch is off is skipped at boot, so it must not raise a camera service and put
 * a privacy indicator on screen for a camera nothing will open.</p>
 */
public final class PeripheralForegroundControlTest {
    /** A VM with these peripherals and video acceleration on, so its media rows are attached. */
    private static DataItem vm(PeripheralType... types) {
        return vm(true, types);
    }

    private static DataItem vm(boolean vpu, PeripheralType... types) {
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        var peripherals = DataItem.newArray();
        for (var type : types) {
            var peripheral = new VMPeripheralConfig(DataItem.newObject());
            peripheral.setType(type);
            peripherals.append(peripheral.item);
        }
        item.set("peripherals", peripherals);
        VpuConfig.setEnabled(item, vpu);
        return item;
    }

    /** The transition the service has to be raised on: STARTING, before crosvm is spawned. */
    @Test
    public void aStartingVmWithACameraAlreadyNeedsTheService() {
        var item = vm(PeripheralType.VIRTIO_CAMERA);
        assertEquals(FOREGROUND_SERVICE_TYPE_CAMERA,
            PeripheralForegroundControl.typesFor(VMState.STARTING, item));
    }

    /** Every state but STOPPED means the device exists; a stopping VM has not let go yet. */
    @Test
    public void everyLiveStateCountsAndStoppedDoesNot() {
        var item = vm(PeripheralType.VIRTIO_CAMERA);
        for (var state : VMState.values()) {
            int want = state == VMState.STOPPED
                ? FOREGROUND_SERVICE_TYPE_NONE : FOREGROUND_SERVICE_TYPE_CAMERA;
            assertEquals(state.name(), want,
                PeripheralForegroundControl.typesFor(state, item));
        }
    }

    /**
     * A refused raise is not remembered as done. {@code applied} is {@code refresh}'s "nothing
     * changed" short-circuit, so recording a failure as success would leave the next transition
     * -- STARTING to RUNNING, wanting the same mask -- with nothing to do, and the VM without
     * the capability for the rest of its life over one transient refusal.
     */
    @Test
    public void aRefusedRaiseIsForgottenSoTheNextTransitionRetries() {
        assertEquals(FOREGROUND_SERVICE_TYPE_CAMERA,
            PeripheralForegroundControl.appliedAfter(FOREGROUND_SERVICE_TYPE_CAMERA, true));
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE,
            PeripheralForegroundControl.appliedAfter(FOREGROUND_SERVICE_TYPE_CAMERA, false));
        // Which is the comparison refresh actually makes, with the same mask still wanted.
        assertNotEquals(FOREGROUND_SERVICE_TYPE_CAMERA,
            PeripheralForegroundControl.appliedAfter(FOREGROUND_SERVICE_TYPE_CAMERA, false));
        // A stop that was accepted is remembered, so it is not re-issued on every event.
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE,
            PeripheralForegroundControl.appliedAfter(FOREGROUND_SERVICE_TYPE_NONE, true));
    }

    /** A VM with no camera never raises one, whatever it is doing. */
    @Test
    public void soundOnlyNeedsNothing() {
        var item = vm(PeripheralType.VIRTIO_SOUND);
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE,
            PeripheralForegroundControl.typesFor(VMState.RUNNING, item));
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE,
            PeripheralForegroundControl.typesFor(VMState.STARTING, vm()));
    }

    /**
     * A camera on a VM with the VPU switch off is a row the backend skips, so the mask stays 0.
     * The service is about devices that exist, and this one will not.
     */
    @Test
    public void aCameraOnAVmWithTheVpuOffAsksForNothing() {
        var item = vm(false, PeripheralType.VIRTIO_CAMERA);
        for (var state : VMState.values())
            assertEquals(state.name(), FOREGROUND_SERVICE_TYPE_NONE,
                PeripheralForegroundControl.typesFor(state, item));
        // ... and turning the switch on, which is what the editor does when the row is added,
        // is the whole difference.
        VpuConfig.setEnabled(item, true);
        assertEquals(FOREGROUND_SERVICE_TYPE_CAMERA,
            PeripheralForegroundControl.typesFor(VMState.RUNNING, item));
    }

    /** A sound card is not a media device: it is unaffected by the VPU switch either way. */
    @Test
    public void theVpuSwitchDoesNotReachANonMediaPeripheral() {
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE,
            PeripheralForegroundControl.typesFor(VMState.RUNNING,
                vm(false, PeripheralType.VIRTIO_SOUND)));
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE,
            PeripheralForegroundControl.typesFor(VMState.RUNNING,
                vm(true, PeripheralType.VIRTIO_SOUND)));
    }

    /** A type the host cannot serve is not attached, so it asks for nothing. */
    @Test
    public void anUnavailableTypeAsksForNothing() {
        var item = vm(PeripheralType.INTEL_HDA);
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE,
            PeripheralForegroundControl.typesFor(VMState.RUNNING, item));
    }

    /** Two cameras on one VM are still one service; the mask is a union, not a count. */
    @Test
    public void twoCamerasAreOneType() {
        var item = vm(PeripheralType.VIRTIO_CAMERA, PeripheralType.VIRTIO_CAMERA,
            PeripheralType.VIRTIO_SOUND);
        assertEquals(FOREGROUND_SERVICE_TYPE_CAMERA,
            PeripheralForegroundControl.typesFor(VMState.RUNNING, item));
    }

    /**
     * What the daemon computes across VMs: the service stays up for the camera VM while the
     * other one stops, and drops only when the last live camera VM does.
     */
    @Test
    public void theMaskIsTheUnionOverEveryVm() {
        var withCamera = vm(PeripheralType.VIRTIO_CAMERA);
        var withSound = vm(PeripheralType.VIRTIO_SOUND);
        int both = PeripheralForegroundControl.typesFor(VMState.RUNNING, withCamera)
            | PeripheralForegroundControl.typesFor(VMState.STARTING, withSound);
        assertEquals(FOREGROUND_SERVICE_TYPE_CAMERA, both);
        int soundLeft = PeripheralForegroundControl.typesFor(VMState.STOPPED, withCamera)
            | PeripheralForegroundControl.typesFor(VMState.RUNNING, withSound);
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE, soundLeft);
        // A second camera VM with the switch off does not hold the service up on its own.
        int vpuOffOnly = PeripheralForegroundControl.typesFor(VMState.STOPPED, withCamera)
            | PeripheralForegroundControl.typesFor(VMState.RUNNING,
                vm(false, PeripheralType.VIRTIO_CAMERA));
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE, vpuOffOnly);
    }

    /**
     * The display hold, over the four shapes a VM can have: no camera, a camera with the switch
     * off, a camera with the switch on, and two VMs with one each. The bit is a union like the
     * mask, so the second VM is what says the hold is not a property of "the VM being looked at".
     *
     * <p>What it is for is defect D76: the CAMERA app op is granted in {@code foreground} mode,
     * a sleeping screen makes the app {@code TOP_SLEEPING}, and cameraserver revokes a session
     * that is already streaming -- the guest sees {@code ENODEV} mid-capture. The foreground
     * service cannot prevent that; a wake lock can, and {@code camera_keep_screen_on} is whether
     * this VM's owner wants it taken.</p>
     */
    @Test
    public void theScreenIsHeldOnlyForACameraVmWhoseSwitchIsOn() {
        // 1. No camera: a VM with a sound card asks for no service and no screen.
        assertFalse(PeripheralForegroundControl.keepsScreenOn(VMState.RUNNING,
            vm(PeripheralType.VIRTIO_SOUND)));
        // 2. A camera, switch off: the service is still raised (the guest gets its camera), the
        //    display is not held, and the documented D76 limitation applies to that VM.
        var off = vm(PeripheralType.VIRTIO_CAMERA);
        VpuConfig.setCameraKeepScreenOn(off, false);
        assertEquals(FOREGROUND_SERVICE_TYPE_CAMERA,
            PeripheralForegroundControl.typesFor(VMState.RUNNING, off));
        assertFalse(PeripheralForegroundControl.keepsScreenOn(VMState.RUNNING, off));
        // 3. A camera, switch on -- and on is the default, so this is a VM nobody configured.
        var on = vm(PeripheralType.VIRTIO_CAMERA);
        assertTrue(VpuConfig.isCameraKeepScreenOn(on));
        assertTrue(PeripheralForegroundControl.keepsScreenOn(VMState.RUNNING, on));
        // 4. Two VMs, one each: the union is what the daemon computes, so one asker is enough.
        assertTrue(PeripheralForegroundControl.keepsScreenOn(VMState.RUNNING, on)
            | PeripheralForegroundControl.keepsScreenOn(VMState.RUNNING, off));
        // ... and the hold drops only when the last of them stops.
        assertFalse(PeripheralForegroundControl.keepsScreenOn(VMState.STOPPED, on)
            | PeripheralForegroundControl.keepsScreenOn(VMState.RUNNING, off));
    }

    /**
     * The hold follows the camera service exactly, which is what makes the two impossible to get
     * out of step: every state that raises the service holds the screen, and the two conditions
     * that stop the service from being raised -- STOPPED, and the VPU switch off, which is a
     * camera row the backend will skip -- stop the hold too.
     */
    @Test
    public void theHoldFollowsTheCameraServiceAndNotTheRow() {
        var item = vm(PeripheralType.VIRTIO_CAMERA);
        for (var state : VMState.values())
            assertEquals(state.name(),
                PeripheralForegroundControl.typesFor(state, item) == FOREGROUND_SERVICE_TYPE_CAMERA,
                PeripheralForegroundControl.keepsScreenOn(state, item));
        // A camera row on a VM with no VPU is not a device, so there is nothing to keep awake for.
        var noVpu = vm(false, PeripheralType.VIRTIO_CAMERA);
        assertTrue(VpuConfig.isCameraKeepScreenOn(noVpu));
        assertFalse(PeripheralForegroundControl.keepsScreenOn(VMState.RUNNING, noVpu));
        // Nor does a peripheral that has no app op to lose, whatever the switch says.
        var sound = vm(PeripheralType.VIRTIO_SOUND);
        VpuConfig.setCameraKeepScreenOn(sound, true);
        assertFalse(PeripheralForegroundControl.keepsScreenOn(VMState.RUNNING, sound));
    }

    /**
     * A refused request loses the hold as well as the mask. The two ride one intent, so believing
     * the hold was applied after a refusal would leave the next transition with nothing to do --
     * and a camera VM running its whole life with the screen free to sleep, which is D76 reached
     * through the retry path instead of through the switch.
     */
    @Test
    public void aRefusedRaiseForgetsTheHoldToo() {
        assertTrue(PeripheralForegroundControl.keepScreenOnAfter(true, true));
        assertFalse(PeripheralForegroundControl.keepScreenOnAfter(true, false));
        assertFalse(PeripheralForegroundControl.keepScreenOnAfter(false, true));
        // Which is the same rule the mask follows, spelt for a boolean.
        assertEquals(PeripheralForegroundControl.appliedAfter(FOREGROUND_SERVICE_TYPE_CAMERA, false)
            != 0, PeripheralForegroundControl.keepScreenOnAfter(true, false));
    }
}
