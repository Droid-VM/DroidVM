// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The pen's tilt back from Android's AXIS_TILT/AXIS_ORIENTATION to evdev's ABS_TILT_X/Y: the inverse
 * of what Android's input reader computes from a digitizer's tilt.
 */
public class EvdevEncoderTiltTest {
    // Android's TouchInputMapper: orientation = atan2(-sin tx, sin ty), tilt = acos(cos tx * cos ty).
    private static float[] android(double txDeg, double tyDeg) {
        double tx = Math.toRadians(txDeg), ty = Math.toRadians(tyDeg);
        return new float[]{(float) Math.acos(Math.cos(tx) * Math.cos(ty)),
            (float) Math.atan2(-Math.sin(tx), Math.sin(ty))};
    }

    private static void roundTrip(int tx, int ty) {
        float[] axes = android(tx, ty);
        assertArrayEquals(new int[]{tx, ty}, EvdevEncoder.tiltDegrees(axes[0], axes[1]));
    }

    @Test
    public void upright() {
        assertArrayEquals(new int[]{0, 0}, EvdevEncoder.tiltDegrees(0f, 0f));
    }

    @Test
    public void alongOneAxis() {
        roundTrip(30, 0);
        roundTrip(-45, 0);
        roundTrip(0, 60);
        roundTrip(0, -20);
    }

    @Test
    public void bothAxes() {
        roundTrip(30, 40);
        roundTrip(-50, 25);
        roundTrip(-60, -60);
        roundTrip(15, -35);
    }

    @Test
    public void flatClampsToTheBound() {
        // Lying flat, pointing right (the top leans left, towards -X).
        int[] tilt = EvdevEncoder.tiltDegrees((float) (Math.PI / 2), (float) (Math.PI / 2));
        assertEquals(-EvdevEncoder.PEN_TILT_MAX, tilt[0]);
        assertEquals(0, tilt[1]);
    }
}
