// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

/** Fallback for Android versions that capture raw contacts instead of system gestures.
 * Positions are millimetres; pointer IDs form a stable mask, independent of index order.
 */
public final class TouchpadGestureRecognizer {
    private static final int LEFT = 0x110, RIGHT = 0x111;
    private final PointerEventQueue.Output out;
    private final boolean natural;
    private long fingers, started;
    private int count, maxCount, buttons, primary, emittedButtons;
    private float lastX, lastY, travel, moveX, moveY, wheelX, wheelY;
    private boolean clicked;
    public TouchpadGestureRecognizer(PointerEventQueue.Output out, boolean natural) {
        this.out = out; this.natural = natural;
    }
    public void frame(long nextFingers, float xMm, float yMm, long time, int nextButtons) {
        int nextCount = Long.bitCount(nextFingers);
        if (count == 0 && nextCount > 0) {
            started = time; travel = 0; maxCount = 0; clicked = buttons != 0;
            clearRemainders();
        }
        maxCount = Math.max(maxCount, nextCount);
        // A primary press with two raw contacts is a secondary click.
        updateButtons(nextButtons, nextCount);
        if (nextCount > 0 && nextFingers == fingers) {
            float dx = xMm - lastX, dy = yMm - lastY;
            travel += (float) Math.hypot(dx, dy);
            if (nextCount == 1 || primary != 0) {
                moveX += dx * 12; moveY += dy * 12;
                int x = (int) moveX, y = (int) moveY;
                moveX -= x; moveY -= y;
                if (x != 0 || y != 0) out.move(x, y);
            } else if (nextCount == 2) {
                wheelX += dx / 3; wheelY -= dy / 3;
                int h = (int) wheelX, v = (int) wheelY;
                wheelX -= h; wheelY -= v;
                if (h != 0 || v != 0) out.scroll(natural ? -v : v, natural ? -h : h);
            }
        }
        if (nextCount == 0 && count > 0 && !clicked && travel < 2
                && time - started <= 250 && maxCount <= 2) {
            int code = maxCount == 2 ? RIGHT : LEFT;
            out.button(code, true); out.button(code, false);
        }
        if (nextFingers != fingers) clearRemainders();
        fingers = nextFingers; count = nextCount; lastX = xMm; lastY = yMm;
    }
    public void setButtons(int next) { updateButtons(next, count); }
    private void updateButtons(int next, int contacts) {
        next &= 31;
        if (((buttons ^ next) & 1) != 0) {
            if ((next & 1) != 0) {
                primary = contacts >= 2 ? RIGHT : LEFT;
                clicked = true;
            } else primary = 0;
        }
        if (((buttons | next) & ~1) != 0) cancelTap();
        // Remapped primary and explicit secondary can both own the guest right button.
        // Emit their union so releasing either source cannot release the other source.
        int nextEmitted = (next & ~1) | (primary == 0 ? 0 : 1 << (primary - LEFT));
        int changed = emittedButtons ^ nextEmitted;
        emittedButtons = nextEmitted;
        for (int bit = 0; bit < 5; bit++)
            if ((changed & (1 << bit)) != 0)
                out.button(LEFT + bit, (nextEmitted & (1 << bit)) != 0);
        buttons = next;
    }
    private void clearRemainders() { moveX = moveY = wheelX = wheelY = 0; }
    public void cancelTap() { clicked = true; }
    public void reset() {
        updateButtons(0, 0);
        fingers = 0; count = maxCount = 0; clicked = false;
        clearRemainders();
    }
}
