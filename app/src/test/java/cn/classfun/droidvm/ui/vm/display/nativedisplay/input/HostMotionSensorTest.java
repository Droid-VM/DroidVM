// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class HostMotionSensorTest {
    private static final int G = EvdevEncoder.MOTION_ACCEL_PER_G;

    @Test
    public void aQuarterTurnTakesXyToMinusYx() {
        // Gravity straight down a frame, (0, -g), seen from a frame turned a quarter clockwise
        // from it: that frame's X axis now points down, so the same vector reads (g, 0).
        assertArrayEquals(new int[]{G, 0}, HostMotionSensor.turn(0, -G, 1));
        assertArrayEquals(new int[]{0, G}, HostMotionSensor.turn(0, -G, 2));
        assertArrayEquals(new int[]{-G, 0}, HostMotionSensor.turn(0, -G, 3));
        // Four quarter turns, and turns past four, come back round.
        assertArrayEquals(new int[]{3, 5}, HostMotionSensor.turn(3, 5, 4));
        assertArrayEquals(HostMotionSensor.turn(3, 5, 1), HostMotionSensor.turn(3, 5, 5));
    }

    @Test
    public void androidRotation90IsAQuarterTurn() {
        // Display.getRotation() == ROTATION_90: the phone turned a quarter anticlockwise, so its
        // natural right edge is up. Gravity, along -X in the phone's frame, is straight down the
        // console's screen once turned by R = 1: (-g, 0) -> (0, -g).
        assertArrayEquals(new int[]{0, -G}, HostMotionSensor.turn(-G, 0, 1));
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
