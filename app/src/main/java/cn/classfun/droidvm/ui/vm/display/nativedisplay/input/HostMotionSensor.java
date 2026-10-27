// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;

import java.util.function.IntSupplier;

/**
 * Corrects the guest's motion sensors for how this console shows the guest (MotionSensors), while it
 * is in front. crosvm feeds them the phone's own sensors in the phone's natural frame; what this
 * writes to the VM's SENSOR channel overrides that for as long as it keeps coming (crosvm's
 * {@code --input motion}).
 *
 * <p>The guest's panel is the phone's frame turned twice: by R, the quarter turns Android gives this
 * console's window (Display.getRotation), and by D, the Display orientation, the quarter turns the
 * console shows the guest's picture at in its window. The phone's motion goes through both
 * ({@link #turn}), so the guest sees gravity down the picture as it is shown, whoever turns what: R
 * is the display's real turn (a window the system letterboxes rather than turns needs nothing more),
 * and the system's auto-rotate does not enter into it. Without correction the app does not override
 * and the guest gets the phone's own values from crosvm.</p>
 *
 * <p>The values follow crosvm's device: acceleration as the gravity vector (upright, Y is -1 g;
 * Android reports the reaction, +9.81, so it is negated), angular velocity counter-clockwise
 * positive, both in EvdevEncoder's units. Main thread.</p>
 */
public final class HostMotionSensor implements SensorEventListener {
    private final SensorManager sensors;
    private final InputForwarder forwarder;
    private final IntSupplier displayRotation;
    private boolean listening = false;
    private int pictureQuarterTurns = 0;

    /**
     * @param displayRotation this console's display rotation in quarter turns (Display.getRotation)
     */
    public HostMotionSensor(@NonNull SensorManager sensors, @NonNull InputForwarder forwarder,
                            @NonNull IntSupplier displayRotation) {
        this.sensors = sensors;
        this.forwarder = forwarder;
        this.displayRotation = displayRotation;
    }

    /** The Display orientation. */
    public void setPicture(int quarterTurns) {
        this.pictureQuarterTurns = quarterTurns & 3;
    }

    public void start() {
        if (listening) return;
        for (int type : new int[]{Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE}) {
            var sensor = sensors.getDefaultSensor(type);
            if (sensor != null)
                sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
        listening = true;
    }

    /** Stops overriding: about a second later crosvm feeds the phone's sensors again. */
    public void stop() {
        if (!listening) return;
        sensors.unregisterListener(this);
        listening = false;
    }

    @Override
    public void onSensorChanged(@NonNull SensorEvent event) {
        int type = event.sensor.getType();
        boolean angular = type == Sensor.TYPE_GYROSCOPE;
        if (!angular && type != Sensor.TYPE_ACCELEROMETER) return;
        float sign = angular ? 1 : -1;
        float[] v = {sign * event.values[0], sign * event.values[1], sign * event.values[2]};
        v = turn(turn(v, displayRotation.getAsInt()), pictureQuarterTurns);
        forwarder.sendMotion(angular
            ? EvdevEncoder.encodeMotion(true, gyroUnits(v[0]), gyroUnits(v[1]), gyroUnits(v[2]),
            event.timestamp)
            : EvdevEncoder.encodeMotion(false, accelUnits(v[0]), accelUnits(v[1]), accelUnits(v[2]),
            event.timestamp));
    }

    @Override
    public void onAccuracyChanged(@NonNull Sensor sensor, int accuracy) {
    }

    /**
     * A vector in a frame turned [quarterTurns] quarter turns clockwise from the one it was measured
     * in: each turn takes (x, y) to (-y, x). Z, out of the screen, does not change.
     */
    @NonNull
    static float[] turn(@NonNull float[] v, int quarterTurns) {
        float x = v[0], y = v[1];
        for (int i = 0; i < (quarterTurns & 3); i++) {
            float previousX = x;
            x = -y;
            y = previousX;
        }
        return new float[]{x, y, v[2]};
    }


    static int accelUnits(float metersPerS2) {
        long units = Math.round(metersPerS2 / SensorManager.STANDARD_GRAVITY
            * EvdevEncoder.MOTION_ACCEL_PER_G);
        return (int) Math.max(-EvdevEncoder.MOTION_ACCEL_MAX,
            Math.min(EvdevEncoder.MOTION_ACCEL_MAX, units));
    }

    static int gyroUnits(float radiansPerS) {
        long units = Math.round(Math.toDegrees(radiansPerS) * EvdevEncoder.MOTION_GYRO_PER_DEG_S);
        return (int) Math.max(-EvdevEncoder.MOTION_GYRO_MAX - 1,
            Math.min(EvdevEncoder.MOTION_GYRO_MAX, units));
    }
}
