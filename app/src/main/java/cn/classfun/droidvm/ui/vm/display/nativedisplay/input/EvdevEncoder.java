// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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

    /**
     * Fixed ABS_X/ABS_Y (and ABS_MT_POSITION_X/Y) maximum the guest absolute-mouse / multi-touch
     * devices advertise when the daemon omits an explicit resolution ({@code --input
     * absolute-mouse}/{@code multi-touch} with no width/height). View coordinates are scaled to this
     * range against the on-screen view size, so the guest maps them 1:1 to its screen at any
     * resolution -- resolution-independent and auto-resize-proof. MUST equal crosvm's
     * {@code NORMALIZED_ABS_MAX} (config.rs) and {@code VNC_ABS_MAX} (gpu_display_vnc.rs).
     */
    public static final int NORMALIZED_ABS_MAX = 0x7FFF;

    private static final short BTN_TOUCH = 0x14a;

    // Relative mouse (InputMode.MOUSE).
    private static final short EV_REL = 0x02;
    private static final short REL_X = 0x00;
    private static final short REL_Y = 0x01;
    private static final short REL_WHEEL = 0x08;
    private static final short REL_HWHEEL = 0x06;
    // Motion sensors (crosvm --input motion): EV_MSC timestamps, and the first angular-velocity
    // axis (ABS_RX; RY and RZ follow it as Y and Z follow ABS_X).
    private static final short EV_MSC = 0x04;
    private static final short MSC_TIMESTAMP = 0x05;
    private static final short ABS_RX = 0x03;
    /**
     * The motion sensors' units and bounds: per g of acceleration and per degree per second of
     * angular velocity. MUST equal crosvm's MOTION_ACCEL_PER_G, MOTION_ACCEL_MAX,
     * MOTION_GYRO_PER_DEG_S and MOTION_GYRO_MAX (devices/src/virtio/input/defaults.rs).
     */
    public static final int MOTION_ACCEL_PER_G = 256;
    public static final int MOTION_ACCEL_MAX = 16 * MOTION_ACCEL_PER_G;
    public static final int MOTION_GYRO_PER_DEG_S = 16;
    public static final int MOTION_GYRO_MAX = 32767;
    /**
     * The magnetometer's axes and units on a motion device that has one: the field on the first
     * three hat axes per microtesla, the heading on the fourth in hundredths of a degree. MUST equal
     * crosvm's MOTION_MAGN_FIRST_AXIS, MOTION_HEADING_AXIS, MOTION_MAGN_PER_UT, MOTION_MAGN_MAX and
     * MOTION_HEADING_PER_DEG (devices/src/virtio/input/defaults.rs).
     */
    private static final short ABS_HAT0X = 0x10;
    private static final short ABS_HAT1Y = 0x13;
    public static final int MOTION_MAGN_PER_UT = 16;
    public static final int MOTION_MAGN_MAX = 32767;
    public static final int MOTION_HEADING_PER_DEG = 100;
    public static final short BTN_LEFT = 0x110;
    public static final short BTN_RIGHT = 0x111;
    public static final short BTN_MIDDLE = 0x112;
    // Pen (crosvm --input stylus).
    private static final short ABS_PRESSURE = 0x18;
    private static final short ABS_DISTANCE = 0x19;
    private static final short ABS_TILT_X = 0x1a;
    private static final short ABS_TILT_Y = 0x1b;
    private static final short BTN_TOOL_PEN = 0x140;
    private static final short BTN_TOOL_RUBBER = 0x141;
    private static final short BTN_STYLUS = 0x14b;
    private static final short BTN_STYLUS2 = 0x14c;
    /**
     * The pen's pressure and distance ranges and its tilt bound in degrees. MUST equal crosvm's
     * STYLUS_PRESSURE_MAX, STYLUS_DISTANCE_MAX and STYLUS_TILT_MAX (devices/src/virtio/input/defaults.rs).
     */
    public static final int PEN_PRESSURE_MAX = 4095;
    public static final int PEN_DISTANCE_MAX = 255;
    public static final int PEN_TILT_MAX = 90;

    /** Live Android pointer id -> evdev MT slot. Touched only on the worker thread. */
    private final Map<Integer, Integer> pointerSlots = new HashMap<>();
    /** Last guest-space position sent per live pointer id, for the keepalive re-send. */
    private final Map<Integer, int[]> pointerPos = new HashMap<>();
    /**
     * Pointers that went down off the panel (the letterbox around the picture) and have not
     * reached it yet, with their last position. Such a finger touches the guest only once it
     * slides onto the panel: a touchdown where its path crossed the edge, as a real finger coming
     * off the bezel would. A tap or a drag that never reaches the panel sends nothing.
     */
    private final Map<Integer, float[]> outsidePos = new HashMap<>();

    // The pen as last sent: the tool in range (0 when none, else BTN_TOOL_PEN or BTN_TOOL_RUBBER),
    // whether it touches, and its barrel buttons.
    private short penTool;
    private boolean penTouch;
    private boolean penPrimary, penSecondary;

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
     * One magnetometer frame for the motion device: the field (device units) and the heading, or
     * a negative heading when there is none to give (free fall, a field along gravity).
     */
    @NonNull
    public static byte[] encodeMagnetic(int x, int y, int z, int heading, long timestampNs) {
        var events = new ArrayList<Event>(List.of(
            new Event(EV_ABS, ABS_HAT0X, x),
            new Event(EV_ABS, (short) (ABS_HAT0X + 1), y),
            new Event(EV_ABS, (short) (ABS_HAT0X + 2), z)));
        if (heading >= 0) events.add(new Event(EV_ABS, ABS_HAT1Y, heading));
        events.add(new Event(EV_MSC, MSC_TIMESTAMP, (int) (timestampNs / 1000)));
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        return encode(events);
    }

    /**
     * One motion-sensor frame: three axes (acceleration on ABS_X/Y/Z, or with [angularVelocity] on
     * ABS_RX/RY/RZ) in the device's units, the timestamp, SYN.
     */
    @NonNull
    public static byte[] encodeMotion(boolean angularVelocity, int x, int y, int z,
                                      long timestampNs) {
        short first = angularVelocity ? ABS_RX : ABS_X;
        return encode(List.of(
            new Event(EV_ABS, first, x),
            new Event(EV_ABS, (short) (first + 1), y),
            new Event(EV_ABS, (short) (first + 2), z),
            // Microseconds, wrapping, as crosvm's own feed and hid-playstation send it.
            new Event(EV_MSC, MSC_TIMESTAMP, (int) (timestampNs / 1000)),
            new Event(EV_SYN, SYN_REPORT, 0)));
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

    /** Relative pointer motion for {@code InputMode.MOUSE}; null if there is no movement. */
    @Nullable
    public static byte[] encodeMouseMove(int dx, int dy) {
        if (dx == 0 && dy == 0) return null;
        var events = new ArrayList<Event>(3);
        if (dx != 0) events.add(new Event(EV_REL, REL_X, dx));
        if (dy != 0) events.add(new Event(EV_REL, REL_Y, dy));
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        return encode(events);
    }

    /** Mouse button ({@link #BTN_LEFT}/{@link #BTN_RIGHT}/{@link #BTN_MIDDLE}) press or release. */
    @NonNull
    public static byte[] encodeMouseButton(short button, boolean down) {
        var events = new ArrayList<Event>(2);
        events.add(new Event(EV_KEY, button, down ? 1 : 0));
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        return encode(events);
    }

    /** Scroll wheel: vertical (REL_WHEEL, +up/-down) and horizontal (REL_HWHEEL) notches; null if 0. */
    @Nullable
    public static byte[] encodeMouseWheel(int vNotches, int hNotches) {
        if (vNotches == 0 && hNotches == 0) return null;
        var events = new ArrayList<Event>(3);
        if (vNotches != 0) events.add(new Event(EV_REL, REL_WHEEL, vNotches));
        if (hNotches != 0) events.add(new Event(EV_REL, REL_HWHEEL, hNotches));
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        return encode(events);
    }

    /**
     * Absolute-mouse "tablet" ({@code InputMode.TABLET}): the primary pointer mapped onto the guest
     * absolute mouse's ABS_X/ABS_Y, with BTN_LEFT for the touch/click. Because the guest device is an
     * absolute pointer (qemu usb-tablet), not a BTN_TOUCH touchscreen, the same device also carries
     * hover ({@link #encodeAbsMove}), right/middle click ({@link #encodeMouseButton}) and scroll
     * ({@link #encodeMouseWheel}). ABS range must equal the guest resolution
     * ({@code --input absolute-mouse[width=guestW,height=guestH]}).
     *
     * @param scaleX guestWidth / viewWidth
     * @param scaleY guestHeight / viewHeight
     */
    @Nullable
    public byte[] encodeTablet(@NonNull MotionEvent event, float scaleX, float scaleY) {
        int x = (int) (event.getX() * scaleX);
        int y = (int) (event.getY() * scaleY);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                var events = new ArrayList<Event>(4);
                events.add(new Event(EV_ABS, ABS_X, x));
                events.add(new Event(EV_ABS, ABS_Y, y));
                events.add(new Event(EV_KEY, BTN_LEFT, 1));
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                return encode(events);
            }
            case MotionEvent.ACTION_MOVE: {
                var events = new ArrayList<Event>(3);
                events.add(new Event(EV_ABS, ABS_X, x));
                events.add(new Event(EV_ABS, ABS_Y, y));
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                return encode(events);
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                var events = new ArrayList<Event>(2);
                events.add(new Event(EV_KEY, BTN_LEFT, 0));
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                return encode(events);
            }
            default:
                return null;
        }
    }

    /**
     * Absolute pointer position with no button held (hover) for the absolute-mouse/tablet device.
     * Coordinates are already in guest space.
     */
    @NonNull
    public static byte[] encodeAbsMove(int x, int y) {
        var events = new ArrayList<Event>(3);
        events.add(new Event(EV_ABS, ABS_X, x));
        events.add(new Event(EV_ABS, ABS_Y, y));
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
                var downs = new ArrayList<Event>();
                var events = new ArrayList<Event>(count * 4 + 1);
                for (int i = 0; i < count; i++) {
                    int id = event.getPointerId(i);
                    float fx = event.getX(i) * scaleX, fy = event.getY(i) * scaleY;
                    Integer slot = pointerSlots.get(id);
                    if (slot == null) {
                        float[] from = outsidePos.get(id);
                        if (from == null) continue; // no DOWN seen for this pointer; ignore
                        if (!onPanel(fx, fy)) {
                            from[0] = fx;
                            from[1] = fy;
                            continue;
                        }
                        outsidePos.remove(id);
                        int[] edge = edgeCrossing(from[0], from[1], fx, fy);
                        touchDown(downs, id, edge[0], edge[1]);
                        continue; // its next move carries it on from the edge
                    }
                    // A contact that leaves the panel stays on it, held at the edge it crossed.
                    int x = clampAbs(fx);
                    int y = clampAbs(fy);
                    pointerPos.put(id, new int[]{x, y});
                    events.add(new Event(EV_ABS, ABS_MT_SLOT, slot));
                    events.add(new Event(EV_ABS, ABS_MT_POSITION_X, x));
                    events.add(new Event(EV_ABS, ABS_MT_POSITION_Y, y));
                    if (i == 0) { // mirror the primary pointer onto the single-touch axes
                        events.add(new Event(EV_ABS, ABS_X, x));
                        events.add(new Event(EV_ABS, ABS_Y, y));
                    }
                }
                if (!events.isEmpty()) events.add(new Event(EV_SYN, SYN_REPORT, 0));
                downs.addAll(events);
                return downs.isEmpty() ? null : encode(downs);
            }
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                int id = event.getPointerId(idx);
                float fx = event.getX(idx) * scaleX, fy = event.getY(idx) * scaleY;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) outsidePos.clear();
                if (!onPanel(fx, fy)) {
                    outsidePos.put(id, new float[]{fx, fy});
                    return null;
                }
                var events = new ArrayList<Event>(9);
                touchDown(events, id, (int) fx, (int) fy);
                return encode(events);
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = event.getPointerId(event.getActionIndex());
                if (outsidePos.remove(id) != null) return null; // never reached the panel
                Integer slot = pointerSlots.remove(id);
                if (slot == null) return null;
                pointerPos.remove(id);
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
                outsidePos.clear();
                if (pointerSlots.isEmpty()) return null;
                var events = new ArrayList<Event>(pointerSlots.size() * 2 + 2);
                for (int slot : pointerSlots.values()) {
                    events.add(new Event(EV_ABS, ABS_MT_SLOT, slot));
                    events.add(new Event(EV_ABS, ABS_MT_TRACKING_ID, -1));
                }
                pointerSlots.clear();
                pointerPos.clear();
                events.add(new Event(EV_KEY, BTN_TOUCH, 0));
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
                return encode(events);
            }
            default:
                return null;
        }
    }

    /**
     * A new contact at (x, y) on the panel, as one report: its slot and tracking id, BTN_TOUCH when
     * it is the first contact, and the primary pointer mirrored onto the single-touch axes.
     */
    private void touchDown(@NonNull ArrayList<Event> events, int id, int x, int y) {
        int slot = allocSlot(id);
        pointerPos.put(id, new int[]{x, y});
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
    }

    /** Whether a position in the normalized ABS range lies on the panel. */
    private static boolean onPanel(float x, float y) {
        return x >= 0 && y >= 0 && x <= NORMALIZED_ABS_MAX && y <= NORMALIZED_ABS_MAX;
    }

    private static int clampAbs(float v) {
        return (int) Math.max(0f, Math.min(NORMALIZED_ABS_MAX, v));
    }

    /**
     * Where the path from (x0, y0), off the panel, to (x1, y1), on it, crosses the panel's edge:
     * the latest of the two axes' entry points along the segment (Liang-Barsky), clamped so
     * rounding cannot leave it a hair outside.
     */
    @NonNull
    static int[] edgeCrossing(float x0, float y0, float x1, float y1) {
        float t = Math.max(entry(x0, x1), entry(y0, y1));
        return new int[]{clampAbs(x0 + (x1 - x0) * t), clampAbs(y0 + (y1 - y0) * t)};
    }

    // The fraction of the way from a to b (b on the panel's range) where the coordinate enters
    // [0, NORMALIZED_ABS_MAX]; 0 when a is already inside it.
    private static float entry(float a, float b) {
        if (a < 0) return a / (a - b);
        if (a > NORMALIZED_ABS_MAX) return (a - NORMALIZED_ABS_MAX) / (a - b);
        return 0f;
    }

    /** Whether any touch contact is currently down (keepalive needed while true). */
    public boolean hasTouchContacts() {
        return !pointerSlots.isEmpty();
    }

    /**
     * Re-sends every live contact at its last position, or null if there are no contacts. Real
     * touch hardware reports at scan rate for as long as a finger is on the glass, even when
     * nothing changes, and guests rely on that: the Windows touch stack ages out a contact whose
     * reports stop (a stationary finger otherwise reads as a lift, then its next micro-movement
     * as a fresh touchdown). This frame is what a quiet scan cycle would have produced.
     */
    @Nullable
    public byte[] encodeTouchKeepalive() {
        if (pointerSlots.isEmpty()) return null;
        var events = new ArrayList<Event>(pointerSlots.size() * 3 + 3);
        for (var entry : pointerSlots.entrySet()) {
            int[] pos = pointerPos.get(entry.getKey());
            if (pos == null) continue;
            int slot = entry.getValue();
            events.add(new Event(EV_ABS, ABS_MT_SLOT, slot));
            events.add(new Event(EV_ABS, ABS_MT_POSITION_X, pos[0]));
            events.add(new Event(EV_ABS, ABS_MT_POSITION_Y, pos[1]));
            if (slot == 0) {
                events.add(new Event(EV_ABS, ABS_X, pos[0]));
                events.add(new Event(EV_ABS, ABS_Y, pos[1]));
            }
        }
        if (events.isEmpty()) return null;
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        return encode(events);
    }

    /** The index of [event]'s stylus (a pen tip or its eraser end), or -1 when it has none. */
    public static int penPointer(@NonNull MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            int tool = event.getToolType(i);
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) return i;
        }
        return -1;
    }

    /**
     * One report of the pen device from a stylus {@link MotionEvent} (touch, hover or button
     * change), or null when the event has no stylus. The tool (tip or eraser) goes in range on its
     * first event; it leaves only by {@link #encodePenLeave}, because Android's HOVER_EXIT and UP
     * are each followed by the pen's next state when it is still there. Positions are scaled into
     * the normalized range as the touch device's; pressure and distance into the device's ranges
     * ([distanceMax] is the stylus' AXIS_DISTANCE maximum, 0 when it reports none); tilt as
     * {@link #tiltDegrees} gives it.
     */
    @Nullable
    public byte[] encodePen(@NonNull MotionEvent event, float scaleX, float scaleY,
                            float distanceMax) {
        int i = penPointer(event);
        if (i < 0) return null;
        boolean touch;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                touch = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                touch = event.getActionIndex() != i || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN;
                break;
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                touch = penTouch;
                break;
            default: // UP, the hovers
                touch = false;
                break;
        }
        short tool = event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ? BTN_TOOL_RUBBER : BTN_TOOL_PEN;
        var events = new ArrayList<Event>(16);
        if (penTool != tool) {
            if (penTool != 0) {
                penRelease(events);
                events.add(new Event(EV_SYN, SYN_REPORT, 0));
            }
            events.add(new Event(EV_KEY, tool, 1));
            penTool = tool;
        }
        events.add(new Event(EV_ABS, ABS_X, clampAbs(event.getX(i) * scaleX)));
        events.add(new Event(EV_ABS, ABS_Y, clampAbs(event.getY(i) * scaleY)));
        float pressure = Math.max(0f, Math.min(1f, event.getPressure(i)));
        events.add(new Event(EV_ABS, ABS_PRESSURE,
            touch ? Math.max(1, Math.round(pressure * PEN_PRESSURE_MAX)) : 0));
        events.add(new Event(EV_ABS, ABS_DISTANCE,
            touch ? 0 : hoverDistance(event.getAxisValue(MotionEvent.AXIS_DISTANCE, i), distanceMax)));
        int[] tilt = tiltDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, i), event.getOrientation(i));
        events.add(new Event(EV_ABS, ABS_TILT_X, tilt[0]));
        events.add(new Event(EV_ABS, ABS_TILT_Y, tilt[1]));
        if (touch != penTouch) {
            events.add(new Event(EV_KEY, BTN_TOUCH, touch ? 1 : 0));
            penTouch = touch;
        }
        // Android names the barrel buttons STYLUS_PRIMARY/SECONDARY, or SECONDARY/TERTIARY when the
        // stylus reports them as a mouse's.
        int buttons = event.getButtonState();
        boolean primary = (buttons & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
        boolean secondary = (buttons & (MotionEvent.BUTTON_STYLUS_SECONDARY | MotionEvent.BUTTON_TERTIARY)) != 0;
        if (primary != penPrimary) {
            events.add(new Event(EV_KEY, BTN_STYLUS, primary ? 1 : 0));
            penPrimary = primary;
        }
        if (secondary != penSecondary) {
            events.add(new Event(EV_KEY, BTN_STYLUS2, secondary ? 1 : 0));
            penSecondary = secondary;
        }
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        return encode(events);
    }

    /** The pen leaving range (lifted, buttons up, the tool released), or null when none is in range. */
    @Nullable
    public byte[] encodePenLeave() {
        if (penTool == 0) return null;
        var events = new ArrayList<Event>(6);
        penRelease(events);
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        return encode(events);
    }

    private void penRelease(@NonNull List<Event> events) {
        if (penTouch) {
            events.add(new Event(EV_KEY, BTN_TOUCH, 0));
            events.add(new Event(EV_ABS, ABS_PRESSURE, 0));
            penTouch = false;
        }
        if (penPrimary) events.add(new Event(EV_KEY, BTN_STYLUS, 0));
        if (penSecondary) events.add(new Event(EV_KEY, BTN_STYLUS2, 0));
        penPrimary = penSecondary = false;
        events.add(new Event(EV_KEY, penTool, 0));
        penTool = 0;
    }

    // A hovering pen's distance in the device's range: AXIS_DISTANCE against its maximum (or as
    // it is when the stylus states none), never 0, which is contact.
    private static int hoverDistance(float distance, float distanceMax) {
        float v = distanceMax > 0 ? distance / distanceMax * PEN_DISTANCE_MAX : distance;
        return Math.max(1, Math.min(PEN_DISTANCE_MAX, Math.round(v)));
    }

    /**
     * The pen's tilt as evdev's ABS_TILT_X/Y (degrees from the normal in the X-Z and Y-Z planes,
     * positive towards +X and +Y) from Android's AXIS_TILT (radians from the normal) and
     * AXIS_ORIENTATION (radians, 0 pointing up, -pi/2 left). The exact inverse of the conversion
     * Android's input reader makes from a digitizer's tilt (TouchInputMapper: orientation =
     * atan2(-sin tx, sin ty), tilt = acos(cos tx * cos ty)), so a pen tablet's tilt reaches the
     * guest as the phone's digitizer reported it.
     */
    @NonNull
    static int[] tiltDegrees(float tilt, float orientation) {
        double t = Math.max(0, Math.min(Math.PI / 2, tilt));
        double s = Math.sin(orientation), c = Math.cos(orientation);
        double k = s * s * c * c;
        double sin2t = Math.sin(t) * Math.sin(t);
        // r = |(sin tx, sin ty)| (up to sqrt 2); cos tx * cos ty = cos t gives
        // k r^4 - r^2 + sin^2 t = 0, whose root that is sin^2 t at k = 0 is the one.
        double r2 = k < 1e-9 ? sin2t : (1 - Math.sqrt(Math.max(0, 1 - 4 * k * sin2t))) / (2 * k);
        double r = Math.sqrt(Math.max(0, Math.min(2, r2)));
        double tx = Math.asin(Math.max(-1, Math.min(1, -r * s)));
        double ty = Math.asin(Math.max(-1, Math.min(1, r * c)));
        return new int[]{clampTilt(Math.toDegrees(tx)), clampTilt(Math.toDegrees(ty))};
    }

    private static int clampTilt(double degrees) {
        return (int) Math.max(-PEN_TILT_MAX, Math.min(PEN_TILT_MAX, Math.round(degrees)));
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
