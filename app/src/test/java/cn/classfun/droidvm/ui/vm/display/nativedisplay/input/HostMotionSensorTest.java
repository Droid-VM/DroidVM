// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class HostMotionSensorTest {
    private static final int G = EvdevEncoder.MOTION_ACCEL_PER_G;

    private static final float EPSILON = 1e-4f;
    private static final float[] DOWN = {0, -1, 0};

    @Test
    public void aQuarterTurnTakesXyToMinusYx() {
        // Gravity straight down a frame, (0, -1), seen from a frame turned a quarter clockwise
        // from it: that frame's X axis now points down, so the same vector reads (1, 0).
        assertArrayEquals(new float[]{1, 0, 0}, HostMotionSensor.turn(DOWN, 1), EPSILON);
        assertArrayEquals(new float[]{0, 1, 0}, HostMotionSensor.turn(DOWN, 2), EPSILON);
        assertArrayEquals(new float[]{-1, 0, 0}, HostMotionSensor.turn(DOWN, 3), EPSILON);
        // Four quarter turns, and turns past four, come back round; Z stays.
        float[] v = {3, 5, 7};
        assertArrayEquals(v, HostMotionSensor.turn(v, 4), EPSILON);
        assertArrayEquals(HostMotionSensor.turn(v, 1), HostMotionSensor.turn(v, 5), EPSILON);
    }

    @Test
    public void correctionMatchesTheExamples() {
        // The guest's panel keeps its size; gravity reaches it turned by R, then by D.
        // Tablet upright, console portrait (R = 0, D = 0): gravity down the guest's panel.
        assertArrayEquals(DOWN, HostMotionSensor.turn(HostMotionSensor.turn(DOWN, 0), 0), EPSILON);
        // Tablet on its side, console landscape (Display.getRotation() == ROTATION_90: the
        // natural right edge up, gravity along the phone's -X): still down the guest's panel.
        float[] sideways = {-1, 0, 0};
        float[] window = HostMotionSensor.turn(sideways, 1);
        assertArrayEquals(DOWN, window, EPSILON);
        // The same, with the picture turned a quarter (D = 1): gravity across the panel, +X.
        assertArrayEquals(new float[]{1, 0, 0}, HostMotionSensor.turn(window, 1), EPSILON);
    }


    @Test
    public void unitsMatchCrosvm() {
        // Android's reaction to gravity is +9.81 upright; the device reports gravity, so callers
        // negate first: one g either way is 256 units, and the bounds clamp.
        assertEquals(G, HostMotionSensor.accelUnits(9.80665f));
        assertEquals(-EvdevEncoder.MOTION_ACCEL_MAX, HostMotionSensor.accelUnits(-1000f));
        assertEquals(180 * EvdevEncoder.MOTION_GYRO_PER_DEG_S,
            HostMotionSensor.gyroUnits((float) Math.PI));
        assertEquals(EvdevEncoder.MOTION_GYRO_MAX, HostMotionSensor.gyroUnits(1000f));
    }

    @Test
    public void motionFrameIsThreeAxesATimestampAndSyn() {
        var frame = ByteBuffer.wrap(EvdevEncoder.encodeMotion(true, 1, -2, 3, 5_000_000L))
            .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(5 * 8, frame.remaining());
        int[][] expected = {
            {3, 3, 1},       // EV_ABS ABS_RX
            {3, 4, -2},      // EV_ABS ABS_RY
            {3, 5, 3},       // EV_ABS ABS_RZ
            {4, 5, 5000},    // EV_MSC MSC_TIMESTAMP, microseconds
            {0, 0, 0},       // EV_SYN SYN_REPORT
        };
        for (var record : expected) {
            assertEquals(record[0], frame.getShort());
            assertEquals(record[1], frame.getShort());
            assertEquals(record[2], frame.getInt());
        }
    }
}
