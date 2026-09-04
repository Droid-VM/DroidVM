// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * The foreground-service rule: any VM that is not STOPPED and carries a device needing a service
 * type keeps that type up, and the daemon runs the union over every VM.
 *
 * <p>{@code refresh} itself walks a {@code VMInstanceStore} and talks to the platform, so what is
 * tested is the rule it folds -- {@code typesFor(state, item)}, which is where every decision
 * actually is. The store walk around it is a fold of exactly this over every instance.</p>
 */
public final class PeripheralForegroundControlTest {
    private static DataItem vm(PeripheralType... types) {
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        var peripherals = DataItem.newArray();
        for (var type : types) {
            var peripheral = new VMPeripheralConfig(DataItem.newObject());
            peripheral.setType(type);
            peripherals.append(peripheral.item);
        }
        item.set("peripherals", peripherals);
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

    /** A VM with no camera never raises one, whatever it is doing. */
    @Test
    public void soundOnlyNeedsNothing() {
        var item = vm(PeripheralType.VIRTIO_SOUND);
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE,
            PeripheralForegroundControl.typesFor(VMState.RUNNING, item));
        assertEquals(FOREGROUND_SERVICE_TYPE_NONE,
            PeripheralForegroundControl.typesFor(VMState.STARTING, vm()));
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
    }
}
