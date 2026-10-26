// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScreenModeTest {
    // A tablet held landscape: its display 3000x2120 as turned.
    private static final int W = 3000, H = 2120;

    @Test
    public void outcomeSaysHowTheSystemAnswered() {
        assertEquals(ScreenMode.Outcome.FOLLOWING, ScreenMode.FOLLOW_SYSTEM.outcome(W, H, W, H));
        // The display has the asked shape (turned to it, or held that way).
        assertEquals(ScreenMode.Outcome.ROTATED, ScreenMode.LANDSCAPE.outcome(W, H, W, H));
        assertEquals(ScreenMode.Outcome.ROTATED, ScreenMode.PORTRAIT.outcome(H, W, H, W));
        // Portrait asked, the display kept landscape, the window got portrait: bars beside it.
        assertEquals(ScreenMode.Outcome.LETTERBOXED, ScreenMode.PORTRAIT.outcome(W, H, 1500, H));
        // Neither changed.
        assertEquals(ScreenMode.Outcome.IGNORED, ScreenMode.PORTRAIT.outcome(W, H, W, H));
    }

    @Test
    public void whoRotates() {
        // Following the system: Android turns the window (or, rotation lock on, nothing does).
        assertTrue(ScreenMode.FOLLOW_SYSTEM.windowFollowsDevice(ScreenMode.Outcome.FOLLOWING, true, false));
        // A forced orientation the display keeps: the window is still, the guest rotates.
        assertFalse(ScreenMode.PORTRAIT.windowFollowsDevice(ScreenMode.Outcome.ROTATED, true, true));
        // Letterboxed or ignored: the display turns with the device while auto-rotate is on...
        assertTrue(ScreenMode.PORTRAIT.windowFollowsDevice(ScreenMode.Outcome.LETTERBOXED, true, true));
        assertTrue(ScreenMode.LANDSCAPE.windowFollowsDevice(ScreenMode.Outcome.IGNORED, true, true));
        // ...and so it does when the asked shape is only how the device is held.
        assertTrue(ScreenMode.LANDSCAPE.windowFollowsDevice(ScreenMode.Outcome.ROTATED, false, true));
        // With the system's rotation lock on, a forced orientation's window stays still.
        assertFalse(ScreenMode.LANDSCAPE.windowFollowsDevice(ScreenMode.Outcome.IGNORED, true, false));
    }

    @Test
    public void theButtonGoesLandscapePortraitSystem() {
        assertEquals(ScreenMode.PORTRAIT, ScreenMode.LANDSCAPE.next());
        assertEquals(ScreenMode.FOLLOW_SYSTEM, ScreenMode.PORTRAIT.next());
        assertEquals(ScreenMode.LANDSCAPE, ScreenMode.FOLLOW_SYSTEM.next());
    }

    @Test
    public void unknownOrdinalFollowsTheSystem() {
        assertEquals(ScreenMode.FOLLOW_SYSTEM, ScreenMode.fromOrdinal(7));
        assertEquals(ScreenMode.PORTRAIT, ScreenMode.fromOrdinal(2));
    }
}
