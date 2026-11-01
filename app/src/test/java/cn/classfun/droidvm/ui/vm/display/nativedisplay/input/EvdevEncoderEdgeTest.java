// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** Where a finger coming in off the letterbox touches down: the point its path crosses the panel's edge. */
public class EvdevEncoderEdgeTest {
    private static final int MAX = EvdevEncoder.NORMALIZED_ABS_MAX;

    @Test
    public void inFromTheLeft() {
        assertArrayEquals(new int[]{0, 1000}, EvdevEncoder.edgeCrossing(-100, 1000, 100, 1000));
    }

    @Test
    public void upFromBelowTheBottomEdge() {
        // y crosses MAX 72.33% of the way: x = 500 + 100 * 0.7233.
        assertArrayEquals(new int[]{572, MAX}, EvdevEncoder.edgeCrossing(500, 40000, 600, 30000));
    }

    @Test
    public void inAcrossACorner() {
        // Off both edges: the later of the two crossings is where the path reaches the panel.
        assertArrayEquals(new int[]{50, 0}, EvdevEncoder.edgeCrossing(-100, -300, 100, 100));
    }

    @Test
    public void inFromTheRight() {
        assertArrayEquals(new int[]{MAX, 2000}, EvdevEncoder.edgeCrossing(MAX + 50, 2000, MAX - 50, 2000));
    }
}
