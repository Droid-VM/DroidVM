// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * The guest's motion sensors (crosvm {@code --input motion}: an accelerometer and a gyroscope)
 * and what feeds them while a console shows the guest.
 *
 * <p>Off, the VM has no such device. Otherwise crosvm feeds it the phone's own sensors whenever
 * nothing overrides them (PHYSICAL and AUTO; VIRTUAL keeps them out), and an open console
 * overrides them: PHYSICAL with the phone's motion turned into the guest's frame (its picture may
 * be shown turned), VIRTUAL with gravity straight down the console's screen, so the guest stays
 * upright in it, and AUTO with one or the other as the Screen orientation says: physical while
 * the guest does the rotating (Screen locked), virtual while Android does.</p>
 */
public enum MotionSensorMode implements StringEnum {
    OFF(R.string.create_vm_motion_sensor_off),
    PHYSICAL(R.string.create_vm_motion_sensor_physical),
    VIRTUAL(R.string.create_vm_motion_sensor_virtual),
    AUTO(R.string.create_vm_motion_sensor_auto);

    public static final String KEY = "motion_sensor";
    // A config from before this key has no motion sensors.
    public static final MotionSensorMode DEFAULT = OFF;

    private final @StringRes int stringId;

    MotionSensorMode(@StringRes int stringId) {
        this.stringId = stringId;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }

    /** Whether crosvm feeds the device from the phone's sensors when no console overrides them. */
    public boolean hostSensors() {
        return this == PHYSICAL || this == AUTO;
    }

    @NonNull
    public static MotionSensorMode fromItem(@NonNull DataItem item) {
        return optEnum(item, KEY, DEFAULT);
    }
}
