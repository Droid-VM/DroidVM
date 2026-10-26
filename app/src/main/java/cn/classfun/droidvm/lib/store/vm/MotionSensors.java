// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * The VM's motion sensors (crosvm {@code --input motion}: an accelerometer and a gyroscope).
 *
 * <p>With them on, crosvm feeds them the phone's own sensors, in the phone's natural frame. With
 * correction on as well, a console that shows the guest overrides that while it is in front, with
 * the same motion turned into the frame of the guest's panel as the console shows it: the turn
 * Android gives the console's window and the console's Display orientation taken off, so the guest
 * sees gravity as it is in the picture (HostMotionSensor has the rule). Without correction the guest
 * gets the phone's natural frame always.</p>
 */
public final class MotionSensors {
    /** Whether the VM has motion sensors. */
    public static final String KEY = "motion_sensor";
    /** Whether consoles correct them for how they show the guest. */
    public static final String KEY_CORRECT = "motion_sensor_correct";
    public static final boolean DEFAULT = false;
    public static final boolean DEFAULT_CORRECT = true;

    private MotionSensors() {
    }

    public static boolean enabled(@NonNull DataItem item) {
        return item.optBoolean(KEY, DEFAULT);
    }

    /** Whether a console showing the guest corrects its motion sensors (they on, and correction on). */
    public static boolean corrected(@NonNull DataItem item) {
        return enabled(item) && item.optBoolean(KEY_CORRECT, DEFAULT_CORRECT);
    }
}
