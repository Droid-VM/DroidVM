package cn.classfun.droidvm.ui.vm.display.base;

import android.graphics.RectF;
import android.os.Handler;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

/**
 * Unified pointer-gesture state machine for the MOUSE and TABLET input modes, shared by the VNC and
 * native display paths. The activity feeds raw touch {@link MotionEvent}s in the coordinates of the
 * gesture surface (the whole display container, letterbox included) together with the rectangle the
 * guest frame is actually rendered into; the translator classifies them and emits semantic
 * callbacks; each display backend maps those onto its wire format (evdev via crosvm --input, or RFB
 * pointer events).
 *
 * Gestures:
 * <ul>
 *   <li>One finger — MOUSE: drag = relative cursor motion (the guest renders the cursor), quick tap
 *       = left click, tap-and-a-half (tap, then touch-and-move within the tap-drag window) = left
 *       drag with no leading click, libinput-style. TABLET: press is committed as a left-button-down
 *       at the absolute position after a short defer window (so a second finger can still turn it
 *       into a right-click), then drag draws; quick tap = left click.</li>
 *   <li>Two fingers — a quick second-finger tap (contact count 1-&gt;2-&gt;1 where finger 2's press
 *       time &lt; threshold and it barely moved) = right click, positioned at finger 1 (tablet).
 *       Two fingers panning together = scroll-wheel notches.</li>
 *   <li>Three fingers — local display zoom/pan: emitted as view-space transform deltas, applied to
 *       the display view only, never sent to the guest.</li>
 * </ul>
 *
 * Active area: the whole gesture surface is usable; only gestures that carry a coordinate to the
 * guest are pinned to the display rect. MOUSE (relative) is never pinned. TABLET pins on where
 * finger 1 went down: press/drag/tap, the right-click anchor and the scroll anchor (the scroll's
 * coordinate is sampled at press time; the panning after it may leave the rect freely). The
 * second finger of a right-click and the three-finger zoom/pan are position-free and work
 * anywhere, letterbox included.
 *
 * TOUCH (multi-touch) mode bypasses this class entirely.
 *
 * All methods run on the UI thread (touch dispatch). The tablet press-defer uses the supplied
 * {@link Handler} (main looper).
 */
public final class PointerGestureTranslator {
    public interface Listener {
        /** MOUSE mode: relative cursor motion, already scaled to guest pixels. */
        void onRelativeMove(float dxGuest, float dyGuest);

        /** TABLET mode: absolute pointer position (no button change), guest pixels. */
        void onAbsoluteMove(float xGuest, float yGuest);

        /** Left button transition. TABLET uses the coordinates; MOUSE may ignore them. */
        void onLeftButton(boolean down, float xGuest, float yGuest);

        /** Quick single-finger tap = left click (down+up). */
        void onLeftTap(float xGuest, float yGuest);

        /** Two-finger quick tap = right click; coordinates are finger 1's position (tablet). */
        void onRightClick(float xGuest, float yGuest);

        /** Two-finger pan, quantized to wheel notches (+v = scroll up, +h = scroll right). */
        void onScroll(int vNotches, int hNotches);

        /**
         * Three-finger pinch/pan: local display transform. scaleFactor is relative to the previous
         * event; pan/focus are view pixels. Never forwarded to the guest.
         */
        void onZoomPan(float scaleFactor, float dxView, float dyView, float focusX, float focusY);
    }

    private static final long TAP_MS = 250;            // max press duration for a tap
    private static final long RIGHT_TAP_MS = 300;      // max finger-2 press duration for right click
    // Mouse tap-and-drag window (libinput-style): after a tap the left button is held DOWN, and its
    // release is deferred this long. A finger returning within the window continues the SAME press
    // -- moving it drags (one clean press, no leading click); a second quick tap becomes a
    // double-click; nothing means a plain click completes. Kept short so single clicks feel
    // immediate; the tradeoff is the tap-drag re-touch must be quick.
    private static final long TAP_DRAG_MS = 100;
    private static final long TABLET_DEFER_MS = 60;    // tablet: defer left-down so 2nd finger can veto
    private static final float TAP_SLOP = 18f;         // view px of travel before a press is a drag
    private static final float SCROLL_NOTCH_PX = 64f;  // view px of two-finger pan per wheel notch

    // TAP_WAIT: mouse tap done, left button held, waiting out the tap-drag window for a return
    // touch. DRAG_HELD: a finger returned within the window, button still held, not yet moved far
    // enough to be a drag (vs a double-tap).
    private enum State {IDLE, PENDING1, DRAG_MOVE, DRAG_LEFT, TAP_WAIT, DRAG_HELD, TWO, SCROLL2,
        THREE, DEAD}

    private final Handler handler;
    private final Listener listener;
    private boolean absolute; // tablet=true, mouse=false

    private State state = State.IDLE;
    // display rect + surface->guest scale, refreshed on every event so layout changes are picked up
    private final RectF dispRect = new RectF(0, 0, 1, 1);
    private float guestW = 1f, guestH = 1f;
    private float scaleX = 1f, scaleY = 1f;

    private int finger1Id = -1;
    private float f1DownX, f1DownY, f1LastX, f1LastY;
    private long f1DownTime;
    // Finger 1 went down inside the display rect: TABLET coordinate ops (press/tap/drag,
    // right-click anchor, scroll anchor) are only valid then. Irrelevant in MOUSE mode.
    private boolean f1InDisplay;

    private int finger2Id = -1;
    private float f2DownX, f2DownY;
    private long f2DownTime;
    private boolean twoMoved;
    private float scrollAccumV, scrollAccumH;
    private float twoLastMidX, twoLastMidY;

    private float threeLastCx, threeLastCy, threeLastSpread;

    private final Runnable tabletCommit = this::commitTabletPress;
    private final Runnable tapRelease = this::commitTapRelease;

    public PointerGestureTranslator(@NonNull Handler handler, @NonNull Listener listener) {
        this.handler = handler;
        this.listener = listener;
    }

    /** Switch between TABLET (absolute) and MOUSE (relative) semantics. Resets in-flight state. */
    public void setAbsolute(boolean absolute) {
        if (this.absolute != absolute) reset();
        this.absolute = absolute;
    }

    public void reset() {
        handler.removeCallbacks(tabletCommit);
        handler.removeCallbacks(tapRelease);
        // Never leave the guest's left button stuck down if we tear down mid-press.
        if (state == State.DRAG_LEFT || state == State.DRAG_HELD || state == State.TAP_WAIT)
            listener.onLeftButton(false, guestX(f1LastX), guestY(f1LastY));
        state = State.IDLE;
        finger1Id = -1;
        finger2Id = -1;
    }

    /**
     * Feeds one touch event. Returns true if consumed.
     *
     * @param displayRect where the guest frame is rendered, in the event's coordinate space (the
     *                    letterbox-fitted display view under any local zoom/pan transform)
     * @param guestWidth  guest coordinate range the display rect maps onto (fb px or the
     *                    normalized evdev ABS range, whatever the backend's wire format wants)
     * @param guestHeight guest coordinate range the display rect maps onto
     */
    public boolean onTouchEvent(@NonNull MotionEvent ev, @NonNull RectF displayRect,
                                float guestWidth, float guestHeight) {
        if (displayRect.width() <= 0f || displayRect.height() <= 0f) return false;
        dispRect.set(displayRect);
        guestW = guestWidth;
        guestH = guestHeight;
        scaleX = guestWidth / displayRect.width();
        scaleY = guestHeight / displayRect.height();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onFirstDown(ev);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                onExtraDown(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                onMove(ev);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                onPointerUp(ev);
                return true;
            case MotionEvent.ACTION_UP:
                onLastUp(ev);
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                return true;
            default:
                return false;
        }
    }

    /** Maps a gesture-surface coordinate to guest px, clamped to the guest bounds. */
    private float guestX(float x) {
        return Math.max(0f, Math.min((x - dispRect.left) * scaleX, guestW - 1f));
    }

    private float guestY(float y) {
        return Math.max(0f, Math.min((y - dispRect.top) * scaleY, guestH - 1f));
    }

    /** Whether TABLET coordinate ops are valid for this gesture (always true for MOUSE). */
    private boolean coordOps() {
        return !absolute || f1InDisplay;
    }

    private void onFirstDown(MotionEvent ev) {
        finger1Id = ev.getPointerId(0);
        f1DownX = f1LastX = ev.getX(0);
        f1DownY = f1LastY = ev.getY(0);
        f1DownTime = ev.getEventTime();
        // Mouse: a finger returning while the previous tap's release is still deferred continues
        // the SAME held press. Moving it becomes a drag (no leading click); a quick lift is the
        // second half of a double-click. See onLastUp/onMove for the split.
        if (!absolute && state == State.TAP_WAIT) {
            handler.removeCallbacks(tapRelease);
            state = State.DRAG_HELD;
            return;
        }
        f1InDisplay = dispRect.contains(f1DownX, f1DownY);
        state = State.PENDING1;
        if (absolute && f1InDisplay) {
            // Tablet: commit the press after the defer window unless a second finger vetoes it.
            // A press outside the display rect has no guest coordinate and never commits; the
            // fingers are still tracked so multi-finger gestures can form from the letterbox.
            handler.postDelayed(tabletCommit, TABLET_DEFER_MS);
        }
    }

    private void commitTabletPress() {
        if (state != State.PENDING1 || !absolute) return;
        state = State.DRAG_LEFT;
        listener.onLeftButton(true, guestX(f1LastX), guestY(f1LastY));
    }

    // Mouse tap-drag window expired with no return touch: the deferred tap is just a click.
    private void commitTapRelease() {
        if (state != State.TAP_WAIT) return;
        listener.onLeftButton(false, guestX(f1LastX), guestY(f1LastY));
        state = State.IDLE;
        finger1Id = -1;
    }

    private void onExtraDown(MotionEvent ev) {
        int count = ev.getPointerCount();
        if (count == 2 && (state == State.PENDING1 || state == State.DRAG_MOVE
            || state == State.DRAG_LEFT || state == State.DRAG_HELD)) {
            handler.removeCallbacks(tabletCommit);
            if (state == State.DRAG_LEFT || state == State.DRAG_HELD) {
                // The press was already committed (or held from a tap); release it before
                // switching to the two-finger gesture.
                listener.onLeftButton(false, guestX(f1LastX), guestY(f1LastY));
            }
            int idx = ev.getActionIndex();
            finger2Id = ev.getPointerId(idx);
            f2DownX = ev.getX(idx);
            f2DownY = ev.getY(idx);
            f2DownTime = ev.getEventTime();
            twoMoved = false;
            scrollAccumV = 0;
            scrollAccumH = 0;
            twoLastMidX = (f1LastX + f2DownX) / 2f;
            twoLastMidY = (f1LastY + f2DownY) / 2f;
            state = State.TWO;
        } else if (count == 3 && (state == State.TWO || state == State.SCROLL2)) {
            state = State.THREE;
            threeLastCx = centroidX(ev);
            threeLastCy = centroidY(ev);
            threeLastSpread = spread(ev);
        } else {
            // 4+ fingers or unexpected ordering: bail until all fingers lift.
            handler.removeCallbacks(tabletCommit);
            state = State.DEAD;
        }
    }

    private void onMove(MotionEvent ev) {
        switch (state) {
            case PENDING1: {
                int idx = ev.findPointerIndex(finger1Id);
                if (idx < 0) return;
                float x = ev.getX(idx), y = ev.getY(idx);
                if (Math.abs(x - f1DownX) > TAP_SLOP || Math.abs(y - f1DownY) > TAP_SLOP) {
                    handler.removeCallbacks(tabletCommit);
                    if (absolute) {
                        if (f1InDisplay) {
                            // Commit press at the original touch point, then drag from there.
                            listener.onLeftButton(true, guestX(f1DownX), guestY(f1DownY));
                            state = State.DRAG_LEFT;
                        }
                        // Outside the display rect a tablet drag has no coordinate; stay
                        // PENDING1 so extra fingers can still form the multi-finger gestures.
                    } else {
                        state = State.DRAG_MOVE;
                    }
                }
                if (coordOps()) emitPointerMove(x, y);
                f1LastX = x;
                f1LastY = y;
                break;
            }
            case DRAG_MOVE:
            case DRAG_LEFT: {
                int idx = ev.findPointerIndex(finger1Id);
                if (idx < 0) return;
                float x = ev.getX(idx), y = ev.getY(idx);
                emitPointerMove(x, y);
                f1LastX = x;
                f1LastY = y;
                break;
            }
            case DRAG_HELD: {
                // Return touch of a tap-drag: the left button is already held. Once it travels
                // past the slop it's a drag; a lift before that (in onLastUp) is a double-click.
                int idx = ev.findPointerIndex(finger1Id);
                if (idx < 0) return;
                float x = ev.getX(idx), y = ev.getY(idx);
                if (Math.abs(x - f1DownX) > TAP_SLOP || Math.abs(y - f1DownY) > TAP_SLOP)
                    state = State.DRAG_LEFT;
                if (state == State.DRAG_LEFT) emitPointerMove(x, y);
                f1LastX = x;
                f1LastY = y;
                break;
            }
            case TWO:
            case SCROLL2: {
                int i1 = ev.findPointerIndex(finger1Id);
                int i2 = ev.findPointerIndex(finger2Id);
                if (i1 < 0 || i2 < 0) return;
                float midX = (ev.getX(i1) + ev.getX(i2)) / 2f;
                float midY = (ev.getY(i1) + ev.getY(i2)) / 2f;
                float dx = midX - twoLastMidX;
                float dy = midY - twoLastMidY;
                if (state == State.TWO
                    && (Math.abs(midX - (f1DownX + f2DownX) / 2f) > TAP_SLOP
                        || Math.abs(midY - (f1DownY + f2DownY) / 2f) > TAP_SLOP)) {
                    twoMoved = true;
                    if (coordOps()) {
                        state = State.SCROLL2;
                        // The scroll's coordinate is taken at press time: pin the guest pointer
                        // to finger 1's down position so the wheel lands under the fingers; the
                        // panning after this is position-free and may leave the display rect.
                        if (absolute)
                            listener.onAbsoluteMove(guestX(f1DownX), guestY(f1DownY));
                    }
                }
                if (state == State.SCROLL2) {
                    // Natural scrolling: content follows the fingers (pan down = wheel up).
                    scrollAccumV += dy;
                    scrollAccumH += dx;
                    int v = (int) (scrollAccumV / SCROLL_NOTCH_PX);
                    int h = (int) (scrollAccumH / SCROLL_NOTCH_PX);
                    if (v != 0 || h != 0) {
                        scrollAccumV -= v * SCROLL_NOTCH_PX;
                        scrollAccumH -= h * SCROLL_NOTCH_PX;
                        listener.onScroll(v, -h);
                    }
                }
                twoLastMidX = midX;
                twoLastMidY = midY;
                f1LastX = ev.getX(i1);
                f1LastY = ev.getY(i1);
                break;
            }
            case THREE: {
                if (ev.getPointerCount() < 3) return;
                float cx = centroidX(ev), cy = centroidY(ev);
                float sp = spread(ev);
                float factor = threeLastSpread > 1f ? sp / threeLastSpread : 1f;
                listener.onZoomPan(factor, cx - threeLastCx, cy - threeLastCy, cx, cy);
                threeLastCx = cx;
                threeLastCy = cy;
                threeLastSpread = sp;
                break;
            }
            default:
                break;
        }
    }

    private void emitPointerMove(float x, float y) {
        if (absolute) {
            listener.onAbsoluteMove(guestX(x), guestY(y));
        } else {
            listener.onRelativeMove((x - f1LastX) * scaleX, (y - f1LastY) * scaleY);
        }
    }

    private void onPointerUp(MotionEvent ev) {
        int id = ev.getPointerId(ev.getActionIndex());
        if ((state == State.TWO || state == State.SCROLL2) && id == finger2Id) {
            long held = ev.getEventTime() - f2DownTime;
            if (state == State.TWO && !twoMoved && held <= RIGHT_TAP_MS && coordOps()) {
                // 1 -> 2 -> 1 with a quick second finger: right click at finger 1's position
                // (finger 2 is position-free and may sit outside the display rect).
                listener.onRightClick(guestX(f1LastX), guestY(f1LastY));
            }
            // Whatever remains of the gesture is spent; ignore finger 1 until it lifts.
            state = State.DEAD;
            finger2Id = -1;
        } else if ((state == State.TWO || state == State.SCROLL2) && id == finger1Id) {
            // Finger 1 left first; treat the same as gesture end.
            state = State.DEAD;
        } else if (state == State.THREE && ev.getPointerCount() - 1 < 3) {
            state = State.DEAD;
        }
    }

    private void onLastUp(MotionEvent ev) {
        handler.removeCallbacks(tabletCommit);
        switch (state) {
            case PENDING1: {
                long held = ev.getEventTime() - f1DownTime;
                if (held <= TAP_MS) {
                    if (absolute) {
                        if (f1InDisplay) listener.onLeftTap(guestX(f1DownX), guestY(f1DownY));
                    } else {
                        // Mouse tap: press now but defer the release, so a finger returning within
                        // the window continues it as a drag (no leading click) or a double-click.
                        listener.onLeftButton(true, guestX(f1DownX), guestY(f1DownY));
                        state = State.TAP_WAIT;
                        handler.postDelayed(tapRelease, TAP_DRAG_MS);
                        return; // keep TAP_WAIT; don't fall through to the IDLE reset
                    }
                } else if (absolute && f1InDisplay) {
                    // Long still hold that never got committed: emit press+release at the position.
                    listener.onLeftButton(true, guestX(f1DownX), guestY(f1DownY));
                    listener.onLeftButton(false, guestX(f1DownX), guestY(f1DownY));
                }
                break;
            }
            case DRAG_HELD:
                // Return touch lifted without dragging: it was a double-tap. Release the held
                // press (completes click 1), then emit a second click.
                listener.onLeftButton(false, guestX(f1LastX), guestY(f1LastY));
                listener.onLeftButton(true, guestX(f1LastX), guestY(f1LastY));
                listener.onLeftButton(false, guestX(f1LastX), guestY(f1LastY));
                break;
            case DRAG_LEFT:
                listener.onLeftButton(false, guestX(f1LastX), guestY(f1LastY));
                break;
            default:
                break;
        }
        state = State.IDLE;
        finger1Id = -1;
        finger2Id = -1;
    }

    private void cancel() {
        handler.removeCallbacks(tabletCommit);
        handler.removeCallbacks(tapRelease);
        if (state == State.DRAG_LEFT || state == State.DRAG_HELD || state == State.TAP_WAIT)
            listener.onLeftButton(false, guestX(f1LastX), guestY(f1LastY));
        state = State.IDLE;
        finger1Id = -1;
        finger2Id = -1;
    }

    private static float centroidX(MotionEvent ev) {
        float s = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) s += ev.getX(i);
        return s / ev.getPointerCount();
    }

    private static float centroidY(MotionEvent ev) {
        float s = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) s += ev.getY(i);
        return s / ev.getPointerCount();
    }

    /** Mean distance of the pointers from their centroid; pinch ratio = spread/lastSpread. */
    private static float spread(MotionEvent ev) {
        float cx = centroidX(ev), cy = centroidY(ev);
        float s = 0;
        for (int i = 0; i < ev.getPointerCount(); i++)
            s += Math.hypot(ev.getX(i) - cx, ev.getY(i) - cy);
        return s / ev.getPointerCount();
    }
}
