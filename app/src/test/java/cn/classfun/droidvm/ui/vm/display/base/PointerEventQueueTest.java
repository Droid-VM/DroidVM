// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PointerEventQueueTest {
    private final ArrayDeque<Runnable> worker = new ArrayDeque<>();
    private final List<String> events = new ArrayList<>();
    private final PointerEventQueue queue = new PointerEventQueue(worker::addLast, new PointerEventQueue.Output() {
        public void move(int x, int y) { events.add(String.format("move %d %d", x, y)); }
        public void button(int code, boolean down) { events.add(String.format("button %d %s", code, down)); }
        public void scroll(int v, int h) { events.add(String.format("scroll %d %d", v, h)); }
    });
    private void drain() { while (!worker.isEmpty()) worker.removeFirst().run(); }
    @Test public void highRateMotionUsesOneWorkerAndOneAccumulatedFrame() {
        for (int i = 0; i < 10000; i++) queue.move(1, -1);
        assertEquals(1, worker.size()); drain();
        assertEquals(List.of("move 10000 -10000"), events);
    }
    @Test public void dragMotionCannotCrossPressOrRelease() {
        queue.move(1, 0); queue.button(272, true); queue.move(2, 0);
        queue.button(272, false); queue.move(3, 0); drain();
        assertEquals(List.of("move 1 0", "button 272 true", "move 2 0", "button 272 false", "move 3 0"), events);
    }
    @Test public void wheelIsABarrierBetweenPointerMoves() {
        queue.move(1, 0); queue.scroll(1, 0); queue.scroll(2, 0); queue.move(3, 0); drain();
        assertEquals(List.of("move 1 0", "scroll 3 0", "move 3 0"), events);
    }
    @Test public void closeDrainsReleaseAndRejectsNewInput() {
        queue.button(272, true); queue.button(272, false); queue.close(); queue.button(272, true); drain();
        assertEquals(List.of("button 272 true", "button 272 false"), events);
    }
    @Test public void oppositeDeltasDoNotEmitEmptyFrames() {
        queue.move(5, 6); queue.move(-5, -6); drain();
        assertTrue(events.isEmpty());
    }
    @Test public void oppositeWheelDirectionsAreDeliveredInOrder() {
        queue.scroll(1, 0); queue.scroll(-1, 0); drain();
        assertEquals(List.of("scroll 1 0", "scroll -1 0"), events);
    }
    @Test public void horizontalReversalIsABarrierEvenIfVerticalDirectionMatches() {
        queue.scroll(1, 2); queue.scroll(1, -1); drain();
        assertEquals(List.of("scroll 1 2", "scroll 1 -1"), events);
    }
    @Test public void reversingAtTheTopOfAPageStillScrollsDown() {
        int[] position = {0};
        var q = new PointerEventQueue(worker::addLast, new PointerEventQueue.Output() {
            public void move(int x, int y) { }
            public void button(int code, boolean down) { }
            public void scroll(int v, int h) { position[0] = Math.max(0, position[0] - v); }
        });
        q.scroll(1, 0); q.scroll(-1, 0); drain();
        assertEquals(1, position[0]);
    }
    @Test public void acceptsNewWorkAfterTheQueueEmpties() {
        queue.move(1, 0); drain(); queue.move(2, 0); drain();
        assertEquals(List.of("move 1 0", "move 2 0"), events);
    }
    @Test public void writeFailureDoesNotDropRelease() {
        List<String> result = new ArrayList<>();
        var q = new PointerEventQueue(worker::addLast, new PointerEventQueue.Output() {
            public void move(int x, int y) { throw new IllegalStateException("transport failed"); }
            public void button(int code, boolean down) { result.add("released"); }
            public void scroll(int v, int h) { }
            public void failed(RuntimeException error) { result.add("failed"); }
        });
        q.move(1, 0); q.button(272, false); drain();
        assertEquals(List.of("failed", "released"), result);
    }
    @Test public void sinkReplacementDrainsOldReleasesBeforeNewPresses() {
        queue.button(272, false); queue.close();
        var replacement = new PointerEventQueue(worker::addLast, new PointerEventQueue.Output() {
            public void move(int x, int y) { }
            public void button(int code, boolean down) { events.add("new press"); }
            public void scroll(int v, int h) { }
        });
        replacement.button(272, true); drain();
        assertEquals(List.of("button 272 false", "new press"), events);
    }
}
