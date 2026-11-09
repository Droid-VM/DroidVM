// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/** Coalesce adjacent deltas without moving motion across a button or wheel boundary. */
public final class PointerEventQueue {
    public interface Output {
        void move(int x, int y);
        void button(int code, boolean down);
        void scroll(int vertical, int horizontal);
        default void failed(RuntimeException error) { error.printStackTrace(); }
    }
    private static final int MOVE = 0, BUTTON = 1, SCROLL = 2;
    private static final class Event {
        final int kind;
        int x, y;
        Event(int kind, int x, int y) { this.kind = kind; this.x = x; this.y = y; }
    }
    private final ArrayDeque<Event> pending = new ArrayDeque<>();
    private final Executor executor;
    private final Output output;
    private boolean scheduled, closed;
    public PointerEventQueue(Executor executor, Output output) {
        this.executor = executor; this.output = output;
    }
    public void move(int x, int y) { if (x != 0 || y != 0) add(MOVE, x, y); }
    public void button(int code, boolean down) { add(BUTTON, code, down ? 1 : 0); }
    public void scroll(int v, int h) { if (v != 0 || h != 0) add(SCROLL, v, h); }
    private synchronized void add(int kind, int x, int y) {
        if (closed) return;
        Event tail = pending.peekLast();
        if (tail != null && tail.kind == kind && (kind == MOVE
                || kind == SCROLL && sameDirection(tail.x, x) && sameDirection(tail.y, y))) {
            tail.x += x; tail.y += y;
        } else pending.addLast(new Event(kind, x, y));
        if (!scheduled) {
            scheduled = true;
            executor.execute(this::drain);
        }
    }
    private static boolean sameDirection(int before, int after) {
        return before == 0 || after == 0 || (before > 0) == (after > 0);
    }
    private void drain() {
        for (;;) {
            Event event;
            synchronized (this) {
                event = pending.pollFirst();
                if (event == null) { scheduled = false; return; }
            }
            try {
                if (event.kind == BUTTON) output.button(event.x, event.y != 0);
                else if (event.x != 0 || event.y != 0) {
                    if (event.kind == MOVE) output.move(event.x, event.y);
                    else output.scroll(event.x, event.y);
                }
            } catch (RuntimeException error) {
                // A failed frame must not prevent later button releases from draining.
                output.failed(error);
            }
        }
    }
    /** Reject new input, but retain releases already queued by the owner. */
    public synchronized void close() { closed = true; }
}
