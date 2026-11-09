// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class TouchpadGestureRecognizerTest {
    private final List<String> events = new ArrayList<>();
    private final PointerEventQueue.Output out = new PointerEventQueue.Output() {
        public void move(int x, int y) { events.add(String.format("move %d %d", x, y)); }
        public void button(int code, boolean down) { events.add(String.format("button %d %s", code, down)); }
        public void scroll(int v, int h) { events.add(String.format("scroll %d %d", v, h)); }
    };
    private final TouchpadGestureRecognizer pad = new TouchpadGestureRecognizer(out, true);
    @Test public void singleFingerTapClicksLeft() {
        pad.frame(1, 10, 10, 100, 0); pad.frame(0, 0, 0, 180, 0);
        assertEquals(List.of("button 272 true", "button 272 false"), events);
    }
    @Test public void staggeredTwoFingerTapDoesNotJump() {
        pad.frame(1, 10, 10, 100, 0); pad.frame(3, 30, 30, 120, 0);
        pad.frame(1, 10, 10, 150, 0); pad.frame(0, 0, 0, 180, 0);
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void physicalMillimetresHaveEqualSpeedOnBothAxes() {
        pad.frame(1, 10, 10, 100, 0); pad.frame(1, 13, 13, 120, 0);
        pad.frame(0, 0, 0, 180, 0);
        assertEquals(List.of("move 36 36"), events);
    }
    @Test public void subNotchScrollAccumulates() {
        pad.frame(3, 10, 10, 100, 0); pad.frame(3, 10, 11, 120, 0);
        pad.frame(3, 10, 12, 140, 0); assertTrue(events.isEmpty());
        pad.frame(3, 10, 13, 160, 0);
        assertEquals(List.of("scroll 1 0"), events);
    }
    @Test public void naturalHorizontalScroll() {
        pad.frame(3, 10, 10, 100, 0); pad.frame(3, 13, 10, 120, 0);
        assertEquals(List.of("scroll 0 -1"), events);
    }
    @Test public void traditionalScroll() {
        var traditional = new TouchpadGestureRecognizer(out, false);
        traditional.frame(3, 10, 10, 100, 0); traditional.frame(3, 10, 13, 120, 0);
        assertEquals(List.of("scroll -1 0"), events);
    }
    @Test public void physicalDragReleasesOnReset() {
        pad.frame(1, 10, 10, 100, 1); pad.frame(1, 11, 10, 120, 1); pad.reset();
        assertEquals(List.of("button 272 true", "move 12 0", "button 272 false"), events);
    }
    @Test public void explicitScrollCancelsPendingTap() {
        pad.frame(1, 10, 10, 100, 0); pad.cancelTap(); pad.frame(0, 0, 0, 180, 0);
        assertTrue(events.isEmpty());
    }
    @Test public void buttonOnlyEventsDoNotCreateContacts() {
        pad.setButtons(1); pad.setButtons(0); pad.reset();
        assertEquals(List.of("button 272 true", "button 272 false"), events);
    }
    @Test public void secondaryButtonDoesNotGenerateLeftTap() {
        pad.frame(1, 10, 10, 100, 2); pad.frame(0, 0, 0, 180, 0);
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void threeFingersDoNotTap() {
        pad.frame(7, 10, 10, 100, 0); pad.frame(0, 0, 0, 180, 0); assertTrue(events.isEmpty());
    }
    @Test public void longPressDoesNotTap() {
        pad.frame(1, 10, 10, 100, 0); pad.frame(0, 0, 0, 500, 0); assertTrue(events.isEmpty());
    }
    @Test public void replacedFingerRebasesWithoutMovement() {
        pad.frame(1, 10, 10, 100, 0); pad.frame(2, 80, 50, 120, 0); assertTrue(events.isEmpty());
    }
    @Test public void highPointerIdsStayDistinct() {
        pad.frame(1L << 31, 10, 10, 100, 0);
        pad.frame((1L << 31) | 1, 30, 30, 120, 0);
        pad.frame(0, 0, 0, 180, 0);
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void twoFingerPhysicalPressUsesSecondaryButton() {
        pad.frame(3, 10, 10, 100, 1); pad.frame(0, 0, 0, 180, 0);
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void resetPreventsAPhantomTapAfterCaptureLoss() {
        pad.frame(1, 10, 10, 100, 0); pad.reset(); pad.frame(0, 0, 0, 180, 0);
        assertTrue(events.isEmpty());
    }
    @Test public void remappedPrimaryReleasePreservesExplicitSecondary() {
        pad.frame(3, 10, 10, 100, 1);
        pad.setButtons(3); pad.setButtons(2);
        assertEquals(List.of("button 273 true"), events);
        pad.setButtons(0);
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void explicitSecondaryReleasePreservesRemappedPrimary() {
        pad.frame(3, 10, 10, 100, 1);
        pad.setButtons(3); pad.setButtons(1);
        assertEquals(List.of("button 273 true"), events);
        pad.setButtons(0);
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void resettingOverlappingRightButtonOwnersReleasesOnlyOnce() {
        pad.frame(3, 10, 10, 100, 1); pad.setButtons(3); pad.reset(); pad.reset();
        assertEquals(List.of("button 273 true", "button 273 false"), events);
    }
    @Test public void ordinaryPrimaryAndSecondaryRemainIndependent() {
        pad.frame(1, 10, 10, 100, 1); pad.setButtons(3); pad.setButtons(2); pad.setButtons(0);
        assertEquals(List.of("button 272 true", "button 273 true", "button 272 false", "button 273 false"), events);
    }
}
