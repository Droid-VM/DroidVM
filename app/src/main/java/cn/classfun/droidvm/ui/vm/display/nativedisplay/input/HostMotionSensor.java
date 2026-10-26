// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.function.IntSupplier;

import cn.classfun.droidvm.lib.store.vm.MotionSensorMode;

/**
 * What a console tells the guest's motion sensors while it shows the guest. crosvm feeds them the
 * phone's sensors itself, in the phone's natural frame; what this writes to the VM's SENSOR
 * channel overrides that for as long as it keeps coming (crosvm's {@code --input motion}).
 *
 * <p>Two things can stand between the phone and the guest's panel: Android may show this console
 * turned (the display's rotation, R), and the console may show the guest's picture turned in it
 * (the Display orientation, D, in quarter turns clockwise). The guest's frame is the phone's
 * turned by both, so a vector goes R + D quarter turns through {@link #turn}.</p>
 *
 * <ul>
 * <li>Physical: the phone's acceleration and angular velocity in the guest's frame, at the
 * sensors' rate.</li>
 * <li>Virtual: gravity straight down this console's screen, turned by D only, and no rotation,
 * resent every {@link #VIRTUAL_PERIOD_MS}: the guest stays upright in the console whatever the
 * phone does, and Android does the rotating.</li>
 * <li>Auto: physical while the guest does the rotating (the Screen is locked), virtual while
 * Android does.</li>
 * </ul>
 *
 * <p>The values follow crosvm's device: acceleration as the gravity vector (upright, Y is -1 g;
 * Android reports the reaction, +9.81, so it is negated), angular velocity counter-clockwise
 * positive, both in EvdevEncoder's units. Main thread.</p>
 */
public final class HostMotionSensor implements SensorEventListener {
    // Well inside crosvm's one-second override hold.
    private static final long VIRTUAL_PERIOD_MS = 200;

    private final MotionSensorMode mode;
    private final SensorManager sensors;
    private final InputForwarder forwarder;
    private final IntSupplier displayRotation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable virtualTick = this::sendVirtual;
    private boolean running = false;
    private boolean listening = false;
    private int pictureQuarterTurns = 0;
    private boolean guestRotates = false;

    /**
     * @param displayRotation this console's display rotation in quarter turns (Display.getRotation)
     */
    public HostMotionSensor(@NonNull MotionSensorMode mode, @NonNull SensorManager sensors,
                            @NonNull InputForwarder forwarder,
                            @NonNull IntSupplier displayRotation) {
        this.mode = mode;
        this.sensors = sensors;
        this.forwarder = forwarder;
        this.displayRotation = displayRotation;
    }

    /** The Display orientation and whether the guest does the rotating (Screen locked). */
    public void setPicture(int quarterTurns, boolean guestRotates) {
        this.pictureQuarterTurns = quarterTurns & 3;
        this.guestRotates = guestRotates;
        if (running) apply();
    }

    public void start() {
        running = true;
        apply();
    }

    /** Stops overriding: about a second later crosvm feeds the phone's sensors again. */
    public void stop() {
        running = false;
        apply();
    }

    private boolean physical() {
        return mode == MotionSensorMode.PHYSICAL
            || (mode == MotionSensorMode.AUTO && guestRotates);
    }

    // Listen to the phone's sensors only while they are what is sent, and tick only while gravity
    // is made up here.
    private void apply() {
        boolean wantPhysical = running && mode != MotionSensorMode.OFF && physical();
        boolean wantVirtual = running && mode != MotionSensorMode.OFF && !physical();
        if (wantPhysical && !listening) {
            for (int type : new int[]{Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE}) {
                var sensor = sensors.getDefaultSensor(type);
                if (sensor != null)
                    sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
            }
            listening = true;
        } else if (!wantPhysical && listening) {
            sensors.unregisterListener(this);
            listening = false;
        }
        handler.removeCallbacks(virtualTick);
        if (wantVirtual) sendVirtual();
    }

    private void sendVirtual() {
        // Down the console's screen, then into the guest's frame by the picture's turn alone.
        int[] gravity = turn(0, -EvdevEncoder.MOTION_ACCEL_PER_G, pictureQuarterTurns);
        long now = SystemClock.elapsedRealtimeNanos();
        forwarder.sendMotion(EvdevEncoder.encodeMotion(false, gravity[0], gravity[1], 0, now));
        forwarder.sendMotion(EvdevEncoder.encodeMotion(true, 0, 0, 0, now));
        handler.postDelayed(virtualTick, VIRTUAL_PERIOD_MS);
    }

    @Override
    public void onSensorChanged(@NonNull SensorEvent event) {
        boolean angular = event.sensor.getType() == Sensor.TYPE_GYROSCOPE;
        if (!angular && event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        int x, y, z;
        if (angular) {
            x = gyroUnits(event.values[0]);
            y = gyroUnits(event.values[1]);
            z = gyroUnits(event.values[2]);
        } else {
            x = accelUnits(-event.values[0]);
            y = accelUnits(-event.values[1]);
            z = accelUnits(-event.values[2]);
        }
        int[] turned = turn(x, y, displayRotation.getAsInt() + pictureQuarterTurns);
        forwarder.sendMotion(EvdevEncoder.encodeMotion(angular, turned[0], turned[1], z,
            event.timestamp));
    }

    @Override
    public void onAccuracyChanged(@NonNull Sensor sensor, int accuracy) {
    }

    /**
     * (x, y) in a frame turned [quarterTurns] quarter turns clockwise from the one it was measured
     * in: each turn takes (x, y) to (-y, x). Z, out of the screen, does not change.
     */
    @NonNull
    static int[] turn(int x, int y, int quarterTurns) {
        for (int i = 0; i < (quarterTurns & 3); i++) {
            int previousX = x;
            x = -y;
            y = previousX;
        }
        return new int[]{x, y};
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
