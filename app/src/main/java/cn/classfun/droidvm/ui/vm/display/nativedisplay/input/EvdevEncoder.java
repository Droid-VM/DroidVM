package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes input into the wire format crosvm's virtio-input devices read from a socket: a stream of
 * fixed 8-byte little-endian records {@code (type: u16, code: u16, value: i32)}. crosvm connects to
 * the socket given via {@code --input multi-touch[path=...]} / {@code keyboard[...]} and consumes
 * these records directly. MVP scope: multi-touch + keyboard.
 *
 * Touch encoding is stateful (one instance per session): it keeps a pointer-id -> MT slot mapping
 * because evdev slots must be small reused indices, while Android pointer ids can grow/be sparse;
 * and a contact count so BTN_TOUCH toggles only on the first finger down and the last finger up.
 * All touch methods run on the single InputForwarder worker thread, so the state needs no locking.
 */
public final class EvdevEncoder {
    // from include/uapi/linux/input-event-codes.h
    private static final short EV_SYN = 0x00;
    private static final short EV_KEY = 0x01;
    private static final short EV_ABS = 0x03;

    private static final short SYN_REPORT = 0x00;

    private static final short ABS_X = 0x00;
    private static final short ABS_Y = 0x01;
    private static final short ABS_MT_SLOT = 0x2f;
    private static final short ABS_MT_POSITION_X = 0x35;
    private static final short ABS_MT_POSITION_Y = 0x36;
    private static final short ABS_MT_TRACKING_ID = 0x39;

    private static final short BTN_TOUCH = 0x14a;

    /** Live Android pointer id -> evdev MT slot. Touched only on the worker thread. */
    private final Map<Integer, Integer> pointerSlots = new HashMap<>();

    public EvdevEncoder() {
    }

    private static final class Event {
        final short type;
        final short code;
        final int value;

        Event(short type, short code, int value) {
            this.type = type;
            this.code = code;
            this.value = value;
        }
    }

    /** Serializes events to the contiguous 8-byte-record buffer crosvm reads (one write per batch). */
    @NonNull
    private static byte[] encode(@NonNull List<Event> events) {
        var buf = ByteBuffer.allocate(8 * events.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (var e : events) {
            buf.putShort(e.type);
            buf.putShort(e.code);
            buf.putInt(e.value);
        }
        return buf.array();
    }

    /**
     * Encodes a key down/up plus SYN into a ready-to-send record buffer.
     *
     * @param scanCode Linux evdev KEY_* code (from {@link KeyCodeMapper}).
     */
    @NonNull
    public static byte[] encodeKey(short scanCode, boolean down) {
        var events = new ArrayList<Event>(2);
        events.add(new Event(EV_KEY, scanCode, down ? 1 : 0));
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        return encode(events);
    }

    /**
     * Encodes a touch {@link MotionEvent} into multi-touch evdev records, or null if there is
     * nothing to send. The multi-touch device's ABS range must equal the guest resolution passed
     * via {@code --input multi-touch[width=guestW,height=guestH]}.
     *
     * @param scaleX guestWidth / viewWidth
     * @param scaleY guestHeight / viewHeight
     */
    @Nullable
    public byte[] encodeTouch(@NonNull MotionEvent event, float scaleX, float scaleY) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                int count = event.getPointerCount();
                var events = new ArrayList<Event>(count * 4 + 1);
                for (int i = 0; i < count; i++) {
                    int id = event.getPointerId(i);
                    Integer slot = pointerSlots.get(id);
                    if (slot == null) continue; // no DOWN seen for this pointer; ignore
                    int x = (int) (event.getX(i) * scaleX);
                    int y = (int) (event.getY(i) * scaleY);
                    events.add(new Event(EV_ABS, ABS_MT_SLOT, slot));
                    events.add(new Event(EV_ABS, ABS_MT_POSITION_X, x));
                    events.add(new Event(EV_ABS, ABS_MT_POSITION_Y, y));
                    if (i == 0) { // mirror the primary pointer onto the single-touch axes
                        events.add(new Event(EV_ABS, ABS_X, x));
                        events.add(new Event(EV_ABS, ABS_Y, y));
                    }
                }
                if (events.isEmpty()) return null;
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                return encode(events);
            }
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                int id = event.getPointerId(idx);
                int slot = allocSlot(id);
                int x = (int) (event.getX(idx) * scaleX);
                int y = (int) (event.getY(idx) * scaleY);
                var events = new ArrayList<Event>(8);
                // Only the first contact toggles BTN_TOUCH; further fingers must not re-assert it.
                if (pointerSlots.size() == 1)
                    events.add(new Event(EV_KEY, BTN_TOUCH, 1));
                events.add(new Event(EV_ABS, ABS_MT_SLOT, slot));
                events.add(new Event(EV_ABS, ABS_MT_TRACKING_ID, id));
                events.add(new Event(EV_ABS, ABS_MT_POSITION_X, x));
                events.add(new Event(EV_ABS, ABS_MT_POSITION_Y, y));
                if (slot == 0) {
                    events.add(new Event(EV_ABS, ABS_X, x));
                    events.add(new Event(EV_ABS, ABS_Y, y));
                }
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                return encode(events);
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = event.getPointerId(event.getActionIndex());
                Integer slot = pointerSlots.remove(id);
                if (slot == null) return null;
                var events = new ArrayList<Event>(4);
                events.add(new Event(EV_ABS, ABS_MT_SLOT, slot));
                events.add(new Event(EV_ABS, ABS_MT_TRACKING_ID, -1));
                // Only release BTN_TOUCH once the last finger is up; a POINTER_UP with fingers
                // still down must keep BTN_TOUCH asserted.
                if (pointerSlots.isEmpty())
                    events.add(new Event(EV_KEY, BTN_TOUCH, 0));
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                return encode(events);
            }
            case MotionEvent.ACTION_CANCEL: {
                if (pointerSlots.isEmpty()) return null;
                var events = new ArrayList<Event>(pointerSlots.size() * 2 + 2);
                for (int slot : pointerSlots.values()) {
                    events.add(new Event(EV_ABS, ABS_MT_SLOT, slot));
                    events.add(new Event(EV_ABS, ABS_MT_TRACKING_ID, -1));
                }
                pointerSlots.clear();
                events.add(new Event(EV_KEY, BTN_TOUCH, 0));
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                return encode(events);
            }
            default:
                return null;
        }
    }

    /** Maps a pointer id to a stable slot, allocating the lowest free slot index if new. */
    private int allocSlot(int pointerId) {
        Integer existing = pointerSlots.get(pointerId);
        if (existing != null) return existing;
        int slot = 0;
        while (pointerSlots.containsValue(slot)) slot++;
        pointerSlots.put(pointerId, slot);
        return slot;
    }
}
