package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import android.view.MotionEvent;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes input into the wire format crosvm's virtio-input devices read from a socket: a stream of
 * fixed 8-byte little-endian records {@code (type: u16, code: u16, value: i32)}. crosvm connects to
 * the socket given via {@code --input multi-touch[path=...]} / {@code keyboard[...]} and consumes
 * these records directly. MVP scope: multi-touch + keyboard.
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

    private EvdevEncoder() {
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

    private static void write(@NonNull OutputStream sink, @NonNull List<Event> events)
        throws IOException {
        var buf = ByteBuffer.allocate(8 * events.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (var e : events) {
            buf.putShort(e.type);
            buf.putShort(e.code);
            buf.putInt(e.value);
        }
        // One write per event batch so a SYN_REPORT is never split across writes.
        sink.write(buf.array());
        sink.flush();
    }

    /** @param scanCode Linux evdev KEY_* code (from {@link KeyCodeMapper}). */
    public static void sendKey(@NonNull OutputStream sink, short scanCode, boolean down)
        throws IOException {
        var events = new ArrayList<Event>(2);
        events.add(new Event(EV_KEY, scanCode, down ? 1 : 0));
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        write(sink, events);
    }

    /**
     * Forwards a touch {@link MotionEvent} to a {@code --input multi-touch[...]} device.
     *
     * @param scaleX guestWidth / viewWidth
     * @param scaleY guestHeight / viewHeight
     *               The multi-touch device's ABS range must equal the guest resolution passed via
     *               {@code --input multi-touch[width=guestW,height=guestH]}.
     */
    public static void sendMultiTouch(
        @NonNull OutputStream sink, @NonNull MotionEvent event, float scaleX, float scaleY
    ) throws IOException {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                int count = event.getPointerCount();
                var events = new ArrayList<Event>(count * 6 + 1);
                for (int i = 0; i < count; i++) {
                    int id = event.getPointerId(i);
                    int x = (int) (event.getX(i) * scaleX);
                    int y = (int) (event.getY(i) * scaleY);
                    events.add(new Event(EV_ABS, ABS_MT_SLOT, id));
                    events.add(new Event(EV_ABS, ABS_MT_TRACKING_ID, id));
                    events.add(new Event(EV_ABS, ABS_MT_POSITION_X, x));
                    events.add(new Event(EV_ABS, ABS_MT_POSITION_Y, y));
                    events.add(new Event(EV_ABS, ABS_X, x));
                    events.add(new Event(EV_ABS, ABS_Y, y));
                }
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                write(sink, events);
                break;
            }
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int masked = event.getActionMasked();
                boolean down = masked == MotionEvent.ACTION_DOWN
                    || masked == MotionEvent.ACTION_POINTER_DOWN;
                int idx = event.getActionIndex();
                int id = event.getPointerId(idx);
                int x = (int) (event.getX(idx) * scaleX);
                int y = (int) (event.getY(idx) * scaleY);
                var events = new ArrayList<Event>(8);
                events.add(new Event(EV_KEY, BTN_TOUCH, down ? 1 : 0));
                events.add(new Event(EV_ABS, ABS_MT_SLOT, id));
                events.add(new Event(EV_ABS, ABS_MT_TRACKING_ID, down ? id : -1));
                events.add(new Event(EV_ABS, ABS_MT_POSITION_X, x));
                events.add(new Event(EV_ABS, ABS_MT_POSITION_Y, y));
                events.add(new Event(EV_ABS, ABS_X, x));
                events.add(new Event(EV_ABS, ABS_Y, y));
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                write(sink, events);
                break;
            }
            default:
                break;
        }
    }
}
