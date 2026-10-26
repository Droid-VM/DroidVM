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
 * ({@link #turn}), so the guest sees gravity as it is in the picture. R is the display's real
 * turn, so a window the system letterboxes rather than turns needs nothing more. Hence who rotates:
 * </p>
 * <ul>
 * <li>A window Android holds still: gravity turns in it, and the guest follows gravity as a panel
 * fixed to the window would.</li>
 * <li>A window Android turns with the device (ScreenMode.windowFollowsDevice): gravity stays down
 * the window and the guest's orientation is D's alone. Android turns the window only past its own
 * threshold, though, well beyond the guest's 35 degrees, and the guest would turn first and then
 * back; so gravity is kept within {@link #FOLLOWING_LIMIT_DEGREES} of straight down the window
 * ({@link #keepDown}). Tilt within that, and the rest, pass as they are. With the system's rotation
 * lock on, neither turns.</li>
 * </ul>
 *
 * <p>The values follow crosvm's device: acceleration as the gravity vector (upright, Y is -1 g;
 * Android reports the reaction, +9.81, so it is negated), angular velocity counter-clockwise
 * positive, both in EvdevEncoder's units. Main thread.</p>
 */
public final class HostMotionSensor implements SensorEventListener {
    /**
     * How far from straight down the window gravity may lean while the window follows it: under the
     * angle past which a guest turns (iPadOS here and Linux's iio-sensor-proxy both use 35 degrees).
     */
    static final double FOLLOWING_LIMIT_DEGREES = 30;

    private final SensorManager sensors;
    private final InputForwarder forwarder;
    private final IntSupplier displayRotation;
    private boolean listening = false;
    private int pictureQuarterTurns = 0;
    private boolean windowFollowsDevice = true;

    /**
     * @param displayRotation this console's display rotation in quarter turns (Display.getRotation)
     */
    public HostMotionSensor(@NonNull SensorManager sensors, @NonNull InputForwarder forwarder,
                            @NonNull IntSupplier displayRotation) {
        this.sensors = sensors;
        this.forwarder = forwarder;
        this.displayRotation = displayRotation;
    }

    /** The Display orientation, and whether Android turns the window with the device. */
    public void setPicture(int quarterTurns, boolean windowFollowsDevice) {
        this.pictureQuarterTurns = quarterTurns & 3;
        this.windowFollowsDevice = windowFollowsDevice;
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
        v = turn(v, displayRotation.getAsInt());
        if (!angular && windowFollowsDevice) v = keepDown(v);
        v = turn(v, pictureQuarterTurns);
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

    /**
     * Gravity leaning no more than FOLLOWING_LIMIT_DEGREES from straight down (-Y), in the screen's
     * plane: a direction past it is moved back to it, keeping the length in that plane and Z.
     */
    @NonNull
    static float[] keepDown(@NonNull float[] v) {
        double inPlane = Math.hypot(v[0], v[1]);
        if (inPlane == 0) return v;
        double lean = Math.atan2(v[0], -v[1]);   // from straight down, positive towards +X
        double limit = Math.toRadians(FOLLOWING_LIMIT_DEGREES);
        if (Math.abs(lean) <= limit) return v;
        double kept = Math.copySign(limit, lean);
        return new float[]{(float) (inPlane * Math.sin(kept)), (float) (-inPlane * Math.cos(kept)),
            v[2]};
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
