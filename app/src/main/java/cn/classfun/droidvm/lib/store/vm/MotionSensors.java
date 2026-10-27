// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * The VM's motion sensors (crosvm {@code --input motion}: an accelerometer and a gyroscope).
 *
 * <p>They are a peripheral, {@link PeripheralType#VIRTIO_GYROSCOPE}: a VM has them when its
 * peripheral list carries that row, and the row's correction switch is the second setting. With
 * the sensors on, crosvm feeds them the phone's own sensors, in the phone's natural frame. With
 * correction on as well, a console that shows the guest overrides that while it is in front, with
 * the same motion turned into the frame of the guest's panel as the console shows it: the turn
 * Android gives the console's window and the console's Display orientation taken off, so the guest
 * sees gravity as it is in the picture (HostMotionSensor has the rule). Without correction the guest
 * gets the phone's natural frame always.</p>
 *
 * <p>Before the peripheral existed the two settings were VM-level switches ({@link #LEGACY_KEY},
 * {@link #LEGACY_KEY_CORRECT}). A config that still carries them reads as it always did until the
 * editor saves it, which moves them onto a row ({@link #migrate}).</p>
 */
public final class MotionSensors {
    /** The VM-level switch from before the peripheral: whether the VM has motion sensors. */
    public static final String LEGACY_KEY = "motion_sensor";
    /** The VM-level switch from before the peripheral: whether consoles correct them. */
    public static final String LEGACY_KEY_CORRECT = "motion_sensor_correct";
    /** The row's correction switch. */
    public static final String KEY_CORRECT = "correct";
    public static final boolean DEFAULT_CORRECT = true;

    private MotionSensors() {
    }

    /** The VM's VirtIO GyroScope row, or null. Only the first counts: crosvm has one such device. */
    @Nullable
    public static VMPeripheralConfig rowOf(@NonNull DataItem item) {
        for (var peripheral : VMPeripheralConfig.listOf(item))
            if (peripheral.getType() == PeripheralType.VIRTIO_GYROSCOPE) return peripheral;
        return null;
    }

    public static boolean enabled(@NonNull DataItem item) {
        return rowOf(item) != null || item.optBoolean(LEGACY_KEY, false);
    }

    /** Whether a console showing the guest corrects its motion sensors (they on, and correction on). */
    public static boolean corrected(@NonNull DataItem item) {
        var row = rowOf(item);
        if (row != null) return row.getCorrect();
        return item.optBoolean(LEGACY_KEY, false)
            && item.optBoolean(LEGACY_KEY_CORRECT, DEFAULT_CORRECT);
    }

    /**
     * Moves the VM-level switches onto the peripheral list: sensors that were on become a
     * VirtIO GyroScope row carrying the old correction setting, and both keys go. A config that
     * already has a row keeps it as it is. Running it twice changes nothing.
     */
    public static void migrate(@NonNull DataItem item) {
        boolean legacy = item.optBoolean(LEGACY_KEY, false);
        boolean correct = item.optBoolean(LEGACY_KEY_CORRECT, DEFAULT_CORRECT);
        item.remove(LEGACY_KEY);
        item.remove(LEGACY_KEY_CORRECT);
        if (!legacy || rowOf(item) != null) return;
        var row = VMPeripheralConfig.createDefaultGyroscope();
        row.setCorrect(correct);
        VMXhciConfig.peripherals(item).append(row.item);
    }
}
