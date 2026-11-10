// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PointerButtonsTest {
    private final List<String> events = new ArrayList<>();
    private final PointerButtons buttons = new PointerButtons(new PointerEventQueue.Output() {
        public void move(int x, int y) { }
        public void button(int code, boolean down) { events.add(String.format("%d %s", code, down)); }
        public void scroll(int v, int h) { }
    });
    @Test public void idleTouchpadDoesNotReleaseHeldMouseButton() {
        buttons.set(1, 1); buttons.set(2, 0); buttons.set(1, 0);
        assertEquals(List.of("272 true", "272 false"), events);
    }
    @Test public void twoDevicesCanHoldTheSameButton() {
        buttons.set(1, 1); buttons.set(2, 1); buttons.set(1, 0);
        assertEquals(List.of("272 true"), events);
        buttons.set(2, 0);
        assertEquals(List.of("272 true", "272 false"), events);
    }
    @Test public void disconnectReleasesOnlyTheRemovedDeviceButtons() {
        buttons.set(1, 1); buttons.set(2, 2); buttons.set(1, 0);
        assertEquals(List.of("272 true", "273 true", "272 false"), events);
    }
    @Test public void focusLossReleasesEveryButtonExactlyOnce() {
        buttons.set(1, 31); buttons.set(2, 31); events.clear();
        buttons.reset(); buttons.reset();
        assertEquals(List.of("272 false", "273 false", "274 false", "275 false", "276 false"), events);
    }
}
