package cn.classfun.droidvm.ui.vm.display.base;

import android.os.Handler;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

/**
 * Unified pointer-gesture state machine for the MOUSE and TABLET input modes, shared by the VNC and
 * native display paths. The activity feeds raw touch {@link MotionEvent}s in view coordinates; the
 * translator classifies them and emits semantic callbacks; each display backend maps those onto its
 * wire format (evdev via crosvm --input, or RFB pointer events).
 *
 * Gestures:
 * <ul>
 *   <li>One finger — MOUSE: drag = relative cursor motion (the guest renders the cursor), quick tap
 *       = left click, tap-then-drag (double-tap-hold) = left drag. TABLET: press is committed as a
 *       left-button-down at the absolute position after a short defer window (so a second finger can
 *       still turn it into a right-click), then drag draws; quick tap = left click.</li>
 *   <li>Two fingers — a quick second-finger tap (contact count 1-&gt;2-&gt;1 where finger 2's press
 *       time &lt; threshold and it barely moved) = right click, positioned at finger 1 (tablet).
 *       Two fingers panning together = scroll-wheel notches.</li>
 *   <li>Three fingers — local display zoom/pan: emitted as view-space transform deltas, applied to
 *       the display view only, never sent to the guest.</li>
 * </ul>
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
    private static final long DOUBLE_TAP_MS = 300;     // tap-then-press window for mouse left-drag
    private static final long TABLET_DEFER_MS = 60;    // tablet: defer left-down so 2nd finger can veto
    private static final float TAP_SLOP = 18f;         // view px of travel before a press is a drag
    private static final float SCROLL_NOTCH_PX = 64f;  // view px of two-finger pan per wheel notch

    private enum State {IDLE, PENDING1, DRAG_MOVE, DRAG_LEFT, TWO, SCROLL2, THREE, DEAD}

    private final Handler handler;
    private final Listener listener;
    private boolean absolute; // tablet=true, mouse=false

    private State state = State.IDLE;
    // view->guest scale, refreshed on every event so layout changes are picked up
    private float scaleX = 1f, scaleY = 1f;

    private int finger1Id = -1;
    private float f1DownX, f1DownY, f1LastX, f1LastY;
    private long f1DownTime;
    private long lastTapTime; // completed-tap timestamp for double-tap-hold drag (mouse)

    private int finger2Id = -1;
    private float f2DownX, f2DownY;
    private long f2DownTime;
    private boolean twoMoved;
    private float scrollAccumV, scrollAccumH;
    private float twoLastMidX, twoLastMidY;

    private float threeLastCx, threeLastCy, threeLastSpread;

    private final Runnable tabletCommit = this::commitTabletPress;

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
        state = State.IDLE;
        finger1Id = -1;
        finger2Id = -1;
    }

    /**
     * Feeds one touch event. Returns true if consumed.
     *
     * @param scaleX guestWidth / viewWidth
     * @param scaleY guestHeight / viewHeight
     */
    public boolean onTouchEvent(@NonNull MotionEvent ev, float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
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

    private void onFirstDown(MotionEvent ev) {
        finger1Id = ev.getPointerId(0);
        f1DownX = f1LastX = ev.getX(0);
        f1DownY = f1LastY = ev.getY(0);
        f1DownTime = ev.getEventTime();
        state = State.PENDING1;
        if (!absolute && (ev.getEventTime() - lastTapTime) <= DOUBLE_TAP_MS) {
            // Mouse tap-then-press: hold the left button for a drag.
            state = State.DRAG_LEFT;
            listener.onLeftButton(true, f1DownX * scaleX, f1DownY * scaleY);
        } else if (absolute) {
            // Tablet: commit the press after the defer window unless a second finger vetoes it.
            handler.postDelayed(tabletCommit, TABLET_DEFER_MS);
        }
    }

    private void commitTabletPress() {
        if (state != State.PENDING1 || !absolute) return;
        state = State.DRAG_LEFT;
        listener.onLeftButton(true, f1LastX * scaleX, f1LastY * scaleY);
    }

    private void onExtraDown(MotionEvent ev) {
        int count = ev.getPointerCount();
        if (count == 2 && (state == State.PENDING1 || state == State.DRAG_MOVE
            || state == State.DRAG_LEFT)) {
            handler.removeCallbacks(tabletCommit);
            if (state == State.DRAG_LEFT) {
                // The press was already committed; release it before switching gestures.
                listener.onLeftButton(false, f1LastX * scaleX, f1LastY * scaleY);
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
                        // Commit press at the original touch point, then drag from there.
                        listener.onLeftButton(true, f1DownX * scaleX, f1DownY * scaleY);
                        state = State.DRAG_LEFT;
                    } else {
                        state = State.DRAG_MOVE;
                    }
                }
                emitPointerMove(x, y);
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
                    state = State.SCROLL2;
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
            listener.onAbsoluteMove(x * scaleX, y * scaleY);
        } else {
            listener.onRelativeMove((x - f1LastX) * scaleX, (y - f1LastY) * scaleY);
        }
    }

    private void onPointerUp(MotionEvent ev) {
        int id = ev.getPointerId(ev.getActionIndex());
        if ((state == State.TWO || state == State.SCROLL2) && id == finger2Id) {
            long held = ev.getEventTime() - f2DownTime;
            if (state == State.TWO && !twoMoved && held <= RIGHT_TAP_MS) {
                // 1 -> 2 -> 1 with a quick second finger: right click at finger 1's position.
                listener.onRightClick(f1LastX * scaleX, f1LastY * scaleY);
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
                    listener.onLeftTap(f1DownX * scaleX, f1DownY * scaleY);
                    lastTapTime = ev.getEventTime();
                } else if (absolute) {
                    // Long still hold that never got committed: emit press+release at the position.
                    listener.onLeftButton(true, f1DownX * scaleX, f1DownY * scaleY);
                    listener.onLeftButton(false, f1DownX * scaleX, f1DownY * scaleY);
                }
                break;
            }
            case DRAG_LEFT:
                listener.onLeftButton(false, f1LastX * scaleX, f1LastY * scaleY);
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
        if (state == State.DRAG_LEFT)
            listener.onLeftButton(false, f1LastX * scaleX, f1LastY * scaleY);
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
