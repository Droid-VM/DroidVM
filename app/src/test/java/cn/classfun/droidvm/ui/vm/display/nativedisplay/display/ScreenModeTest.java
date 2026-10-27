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
