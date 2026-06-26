package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.KEYBOARD;
import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.MULTITOUCH;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.SystemClock;
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

    private void submit(@NonNull String name, @NonNull Runnable block) {
        try {
            worker.execute(() -> {
                try {
                    block.run();
                } catch (Exception e) {
                    Log.e(TAG, name + " failed", e);
                }
            });
        } catch (Exception e) {
            Log.d(TAG, name + " dropped: " + e.getMessage());
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
        // eventTime is on the SystemClock.uptimeMillis() timebase, so the diff below is the full
        // finger-to-sink latency (kernel input -> framework dispatch -> our worker -> sink). Read it
        // before recycle() since the framework reuses the MotionEvent afterwards.
        long eventTimeMs = event.getEventTime();
        byte[] data;
        try {
            data = encoder.encodeTouch(event, scaleX, scaleY);
        } catch (Exception e) {
            Log.e(TAG, "encode touch failed", e);
            return;
        } finally {
            event.recycle();
        }
        if (data == null) return; // nothing to send for this event
        long sinkStartNs = System.nanoTime();
        boolean ok = sink.write(MULTITOUCH, data);
        double sinkMs = (System.nanoTime() - sinkStartNs) / 1_000_000.0;
        long e2eMs = SystemClock.uptimeMillis() - eventTimeMs;
        // direct=false means the write fell back to the vm_input JSON-RPC IPC path (~40ms) instead
        // of the direct unix socket (<1ms) - i.e. the UI couldn't reach the daemon's UI input socket.
        boolean direct = sink instanceof DirectInputSink && ((DirectInputSink) sink).wasLastWriteDirect();
        Log.d(TAG, fmt("touch latency: sink=%.2fms e2e=%dms direct=%b delivered=%b",
            sinkMs, e2eMs, direct, ok));
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
