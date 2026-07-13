package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.KEYBOARD;
import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.MOUSE;
import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.MULTITOUCH;
import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.TABLET;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import cn.classfun.droidvm.ui.vm.display.base.InputMode;

import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Centralized input forwarding to the guest VM. Events are encoded with {@link EvdevEncoder} and
 * shipped via {@link InputSink} to the daemon (which owns the per-device unix sockets crosvm reads
 * from) over its IPC. Each high-level event is encoded once and sent in a single sink call. Work is
 * serialized on one background thread; MotionEvents are copied first because the framework recycles
 * the originals.
 *
 * ACTION_MOVE is coalesced: the synchronous per-event IPC round-trip is far slower than the touch
 * sample rate, so unbounded moves would pile up in the worker queue and the on-screen pointer would
 * fall seconds behind the finger. Only the newest pending move is kept; discrete DOWN/UP/POINTER_*
 * events are never dropped and stay ordered. MVP scope: touch + keyboard.
 */
public final class InputForwarder {
    private static final String TAG = "InputForwarder";

    /** Writes pre-encoded evdev bytes for a channel in the root process; false if not delivered. */
    public interface InputSink {
        boolean write(int channel, @NonNull byte[] data);
    }

    private final InputSink sink;
    // Stateful touch encoder (pointer-id -> slot, contact count); single-threaded on the worker.
    private final EvdevEncoder encoder = new EvdevEncoder();

    // Current pointer mode; set from the UI thread, read on the worker. Volatile suffices: a change
    // just takes effect on the next event. TOUCH -> multi-touch, MOUSE -> relative, TABLET -> single.
    private volatile InputMode inputMode = InputMode.TOUCH;
    // Mouse-mode gesture state (InputMode.MOUSE); touched only on the worker thread.
    private float mouseLastX, mouseLastY, mouseDownX, mouseDownY;
    private long mouseDownTime;
    private boolean mouseDragging;
    private static final float MOUSE_TAP_SLOP = 16f;   // guest px of travel before a press is a drag
    private static final long MOUSE_TAP_MS = 250;      // max press duration still treated as a tap
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "InputForwarder");
        t.setDaemon(true);
        return t;
    });

    // Newest pending ACTION_MOVE, coalesced so a fast finger can't outrun the synchronous IPC.
    private final AtomicReference<TouchFrame> pendingMove = new AtomicReference<>();

    private static final class TouchFrame {
        final MotionEvent event;
        final float scaleX;
        final float scaleY;

        TouchFrame(@NonNull MotionEvent event, float scaleX, float scaleY) {
            this.event = event;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }
    }

    public InputForwarder(@NonNull InputSink sink) {
        this.sink = sink;
    }

    /** Switches pointer mode at runtime; takes effect on the next touch event. */
    public void setInputMode(@NonNull InputMode mode) {
        this.inputMode = mode;
    }

    /** The guest pointer device the current mode routes host mouse/stylus events to. */
    private int pointerChannel() {
        return inputMode == InputMode.TABLET ? TABLET : MOUSE;
    }

    /**
     * A pointer button ({@link EvdevEncoder#BTN_RIGHT}/{@link EvdevEncoder#BTN_MIDDLE}) press/release
     * from a host mouse or stylus. Left-click still rides the touch/tap path. Routed to the tablet
     * (absolute mouse) in TABLET mode, otherwise the relative mouse.
     */
    public void sendPointerButton(short button, boolean down) {
        submit("pointerButton", () -> sink.write(pointerChannel(),
            EvdevEncoder.encodeMouseButton(button, down)));
    }

    /** Relative cursor motion (guest px) on the relative-mouse device; the guest renders the cursor. */
    public void sendMouseMove(int dxGuest, int dyGuest) {
        submit("mouseMove", () -> {
            byte[] data = EvdevEncoder.encodeMouseMove(dxGuest, dyGuest);
            if (data != null) sink.write(MOUSE, data);
        });
    }

    /** Absolute pointer position (guest px, no button change) on the tablet device. */
    public void sendAbsMove(int xGuest, int yGuest) {
        submit("absMove", () -> sink.write(TABLET, EvdevEncoder.encodeAbsMove(xGuest, yGuest)));
    }

    /** Left button on the tablet device, positioned first so the press lands at (x, y) guest px. */
    public void sendAbsLeftButton(boolean down, int xGuest, int yGuest) {
        submit("absLeft", () -> {
            sink.write(TABLET, EvdevEncoder.encodeAbsMove(xGuest, yGuest));
            sink.write(TABLET, EvdevEncoder.encodeMouseButton(EvdevEncoder.BTN_LEFT, down));
        });
    }

    /** Host scroll wheel (vertical, horizontal notches) routed to the active pointer device. */
    public void sendScroll(int vNotches, int hNotches) {
        submit("scroll", () -> {
            byte[] data = EvdevEncoder.encodeMouseWheel(vNotches, hNotches);
            if (data != null) sink.write(pointerChannel(), data);
        });
    }

    /**
     * Host pointer hover (no button held). In TABLET mode it becomes an absolute position on the
     * guest tablet (native hover); otherwise a relative delta so the guest mouse cursor follows.
     * Coordinates are view pixels; scale maps them to guest space.
     */
    public void sendHover(float viewX, float viewY, float scaleX, float scaleY) {
        int gx = (int) (viewX * scaleX);
        int gy = (int) (viewY * scaleY);
        submit("hover", () -> {
            if (inputMode == InputMode.TABLET) {
                sink.write(TABLET, EvdevEncoder.encodeAbsMove(gx, gy));
            } else {
                int dx = Math.round(gx - mouseLastX);
                int dy = Math.round(gy - mouseLastY);
                mouseLastX = gx;
                mouseLastY = gy;
                byte[] data = EvdevEncoder.encodeMouseMove(dx, dy);
                if (data != null) sink.write(MOUSE, data);
            }
        });
    }

    private void submit(@NonNull String name, @NonNull Runnable block) {
        try {
            worker.execute(() -> {
                try {
                    block.run();
                } catch (Exception e) {
                    Log.e(TAG, fmt("%s failed", name), e);
                }
            });
        } catch (Exception e) {
            Log.d(TAG, fmt("%s dropped: %s", name, e.getMessage()));
        }
    }

    /**
     * @param scaleX guestWidth / viewWidth
     * @param scaleY guestHeight / viewHeight
     */
    public void sendTouchEvent(@NonNull MotionEvent event, float scaleX, float scaleY) {
        var copy = MotionEvent.obtainNoHistory(event);
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Keep only the latest move; schedule a drain only when the slot was empty so the
            // worker queue never holds more than one pending move regardless of touch rate.
            var prev = pendingMove.getAndSet(new TouchFrame(copy, scaleX, scaleY));
            if (prev != null) {
                prev.event.recycle();
            } else {
                submit("touchMove", this::drainMove);
            }
        } else {
            // DOWN/UP/POINTER_* are discrete state changes; never drop, keep them ordered.
            submit("touchEvent", () -> sendTouchNow(copy, scaleX, scaleY));
        }
    }

    private void drainMove() {
        var frame = pendingMove.getAndSet(null);
        if (frame == null) return; // already superseded; a later drain will (or did) handle it
        sendTouchNow(frame.event, frame.scaleX, frame.scaleY);
    }

    private void sendTouchNow(@NonNull MotionEvent event, float scaleX, float scaleY) {
        try {
            switch (inputMode) {
                case MOUSE:
                    sendMouseNow(event, scaleX, scaleY);
                    break;
                case TABLET: {
                    byte[] data = encoder.encodeTablet(event, scaleX, scaleY);
                    if (data != null) sink.write(TABLET, data);
                    break;
                }
                case TOUCH:
                default: {
                    byte[] data = encoder.encodeTouch(event, scaleX, scaleY);
                    if (data != null) sink.write(MULTITOUCH, data);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "encode/send pointer failed", e);
        } finally {
            event.recycle();
        }
    }

    // Relative-mouse translation (InputMode.MOUSE): a drag becomes REL_X/REL_Y motion, a quick tap
    // without travel becomes a left click. Runs on the worker thread, so the mouse state needs no
    // locking. Move coalescing upstream is fine here: the delta is measured from the last position
    // we actually sent, so dropped intermediate samples just fold into one larger (correct) delta.
    private void sendMouseNow(@NonNull MotionEvent event, float scaleX, float scaleY) {
        float gx = event.getX() * scaleX;
        float gy = event.getY() * scaleY;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mouseLastX = gx;
                mouseLastY = gy;
                mouseDownX = gx;
                mouseDownY = gy;
                mouseDownTime = event.getEventTime();
                mouseDragging = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                int dx = Math.round(gx - mouseLastX);
                int dy = Math.round(gy - mouseLastY);
                if (dx == 0 && dy == 0) break;
                mouseLastX = gx;
                mouseLastY = gy;
                if (!mouseDragging
                    && (Math.abs(gx - mouseDownX) > MOUSE_TAP_SLOP
                        || Math.abs(gy - mouseDownY) > MOUSE_TAP_SLOP)) {
                    mouseDragging = true;
                }
                byte[] data = EvdevEncoder.encodeMouseMove(dx, dy);
                if (data != null) sink.write(MOUSE, data);
                break;
            }
            case MotionEvent.ACTION_UP: {
                boolean tap = !mouseDragging
                    && (event.getEventTime() - mouseDownTime) <= MOUSE_TAP_MS;
                if (tap) {
                    sink.write(MOUSE, EvdevEncoder.encodeMouseButton(EvdevEncoder.BTN_LEFT, true));
                    sink.write(MOUSE, EvdevEncoder.encodeMouseButton(EvdevEncoder.BTN_LEFT, false));
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Forwards a hardware/virtual key by Android key code.
     *
     * @return true if the key code is mapped, false otherwise.
     */
    public boolean sendKeyEvent(int keyCode, boolean pressed) {
        int base = KeyCodeMapper.shiftSynthBase(keyCode);
        if (base != -1) {
            int baseScan = KeyCodeMapper.androidToEvdev(base);
            if (baseScan == -1) return false;
            if (pressed) {
                sendRawKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, true);
                sendRawKeyEvent(baseScan, true);
            } else {
                sendRawKeyEvent(baseScan, false);
                sendRawKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, false);
            }
            return true;
        }
        int scanCode = KeyCodeMapper.androidToEvdev(keyCode);
        if (scanCode == -1) return false;
        sendRawKeyEvent(scanCode, pressed);
        return true;
    }

    /**
     * Sends a printable character as a US-layout evdev key tap, wrapping it in LEFTSHIFT down/up
     * when the character requires Shift (uppercase letters, {@code !@#...}). This is the path for
     * soft keyboards that commit text rather than emitting key events, so uppercase and symbols
     * reach the guest correctly instead of being lost or arriving lowercase.
     *
     * @return true if the character is mapped to a US-layout key; false if the caller should fall
     * back to the framework key character map.
     */
    public boolean sendChar(char c) {
        KeyCodeMapper.CharKey key = KeyCodeMapper.charToKey(c);
        if (key == null) return false;
        if (key.shift) sendRawKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, true);
        sendRawKeyEvent(key.scanCode, true);
        sendRawKeyEvent(key.scanCode, false);
        if (key.shift) sendRawKeyEvent(KeyCodeMapper.KEY_LEFTSHIFT, false);
        return true;
    }

    /** Sends a raw Linux evdev KEY_* scan code. */
    public void sendRawKeyEvent(int scanCode, boolean pressed) {
        submit("sendRawKeyEvent", () -> {
            byte[] data;
            try {
                data = EvdevEncoder.encodeKey((short) scanCode, pressed);
            } catch (Exception e) {
                Log.e(TAG, "encode key failed", e);
                return;
            }
            boolean ok = sink.write(KEYBOARD, data);
            Log.d(TAG, fmt("key scan=%d down=%b delivered=%b", scanCode, pressed, ok));
        });
    }

    /** Shuts down the worker. Sockets are owned by the root process, not closed here. */
    public void close() {
        worker.shutdownNow();
        var frame = pendingMove.getAndSet(null);
        if (frame != null) frame.event.recycle();
    }
}
