// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.TRACKPAD_CONTACTS;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import cn.classfun.droidvm.lib.store.vm.NativeDisplay;

/**
 * Encodes the console's fingers for the VM's trackpad (crosvm {@code --input trackpad[contacts=..]}):
 * one MT slot per finger, BTN_TOUCH and BTN_TOOL_FINGER..QUINTTAP for how many are down,
 * positions in the device's units (a twentieth of a millimetre), contact size as a fraction of the
 * phone panel's own range when it has one, MT_TOOL_PALM for a finger Android took back as
 * accidental, and the frame's time on MSC_TIMESTAMP. No buttons: the guest's taps click.
 *
 * <p>Each event sends the whole frame -- every finger down, where it is -- as a touchpad's firmware
 * does on every scan, and the guest's evdev drops what has not changed. A finger beyond the fifth
 * is left out for as long as it stays down, as a Precision Touchpad must do; it is not handed a
 * slot that frees up under it. Worker thread only, as {@link EvdevEncoder}'s touch state.</p>
 */
public final class TrackpadEncoder {
    private static final short EV_SYN = 0x00;
    private static final short EV_KEY = 0x01;
    private static final short EV_ABS = 0x03;
    private static final short EV_MSC = 0x04;
    private static final short SYN_REPORT = 0x00;
    private static final short MSC_TIMESTAMP = 0x05;
    private static final short ABS_X = 0x00;
    private static final short ABS_Y = 0x01;
    private static final short ABS_MT_SLOT = 0x2f;
    private static final short ABS_MT_TOUCH_MAJOR = 0x30;
    private static final short ABS_MT_TOUCH_MINOR = 0x31;
    private static final short ABS_MT_POSITION_X = 0x35;
    private static final short ABS_MT_POSITION_Y = 0x36;
    private static final short ABS_MT_TOOL_TYPE = 0x37;
    private static final short ABS_MT_TRACKING_ID = 0x39;
    private static final short BTN_TOUCH = 0x14a;
    private static final short MT_TOOL_FINGER = 0;
    private static final short MT_TOOL_PALM = 2;
    // BTN_TOOL_FINGER, DOUBLETAP, TRIPLETAP, QUADTAP, QUINTTAP: indexed by fingers down - 1.
    private static final short[] TOOLS = {0x145, 0x14d, 0x14e, 0x14f, 0x148};
    /** The contact size axes' top. MUST equal crosvm's TRACKPAD_TOUCH_SIZE_MAX. */
    private static final int TOUCH_SIZE_MAX = 255;

    private final int side;
    // Slot -> the Android pointer in it (null when free), its tracking id, position and size.
    private final Integer[] slotPointer = new Integer[TRACKPAD_CONTACTS];
    private final int[][] slotState = new int[TRACKPAD_CONTACTS][4];
    // Pointers down with no slot (a sixth finger, or one that came down while five were); left out
    // until they lift.
    private final Set<Integer> unslotted = new HashSet<>();
    private int nextTrackingId;
    // The BTN_TOOL_* held, 0 when no finger is down.
    private short tool;
    // Per Android input device: its touch major and minor maxima, or null when it reports no size.
    private final Map<Integer, float[]> sizeRanges = new HashMap<>();

    /** @param side the device's ABS range top on both axes, in its units */
    public TrackpadEncoder(int side) {
        this.side = side;
    }

    /**
     * The trackpad's side ({@link NativeDisplay#trackpadSideUnits}) as the daemon sized it: from
     * the phone's built-in display.
     */
    @SuppressWarnings("deprecation")
    public static int sideUnits(@NonNull Context context) {
        var metrics = new DisplayMetrics();
        var display = context.getSystemService(DisplayManager.class)
            .getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return 300 * NativeDisplay.TRACKPAD_UNITS_PER_MM;
        display.getRealMetrics(metrics);
        return NativeDisplay.trackpadSideUnits(metrics);
    }

    /** The trackpad's units per pixel of a view on [context]'s screen, the same both ways. */
    public static float unitsPerPx(@NonNull Context context) {
        var metrics = context.getResources().getDisplayMetrics();
        float dpi = (metrics.xdpi + metrics.ydpi) / 2f;
        return NativeDisplay.TRACKPAD_UNITS_PER_MM * 25.4f / (dpi > 0 ? dpi : 160f);
    }

    /** Whether any finger is down (keepalive frames are due while true). */
    public boolean hasContacts() {
        return tool != 0;
    }

    /**
     * The frame after [event], or null when it changes nothing the trackpad reports.
     *
     * @param unitsX device units per view pixel across
     * @param unitsY device units per view pixel down
     */
    @Nullable
    public byte[] encode(@NonNull MotionEvent event, float unitsX, float unitsY) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        var buf = frame();
        boolean changed = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (action == MotionEvent.ACTION_DOWN) {
                    // A new gesture: nothing of an earlier one can still be down.
                    unslotted.clear();
                    for (int slot = 0; slot < TRACKPAD_CONTACTS; slot++)
                        if (slotPointer[slot] != null) lift(buf, slot, false);
                }
                int id = event.getPointerId(index);
                int slot = freeSlot();
                if (slot < 0) {
                    unslotted.add(id);
                    break;
                }
                slotPointer[slot] = id;
                slotState[slot][1] = slotState[slot][2] = slotState[slot][3] = -1;
                slotState[slot][0] = nextTrackingId;
                nextTrackingId = (nextTrackingId + 1) & 0xffff;
                put(buf, EV_ABS, ABS_MT_SLOT, slot);
                put(buf, EV_ABS, ABS_MT_TRACKING_ID, slotState[slot][0]);
                put(buf, EV_ABS, ABS_MT_TOOL_TYPE, MT_TOOL_FINGER);
                changed = true;
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = event.getPointerId(index);
                if (unslotted.remove(id)) break;
                int slot = slotOf(id);
                if (slot < 0) break;
                // Android takes a finger back as accidental (a palm) with FLAG_CANCELED on its UP.
                lift(buf, slot, (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0);
                changed = true;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                // The whole gesture taken back: every finger lifts as a palm, so none of it taps.
                unslotted.clear();
                for (int slot = 0; slot < TRACKPAD_CONTACTS; slot++) {
                    if (slotPointer[slot] == null) continue;
                    lift(buf, slot, true);
                    changed = true;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                break;
            default:
                return null;
        }
        // Where every finger still down is now; one lifting was let go above, where it last was.
        float[] size = sizeRange(event);
        for (int i = 0; i < event.getPointerCount(); i++) {
            int slot = slotOf(event.getPointerId(i));
            if (slot < 0) continue;
            int x = clamp(Math.round(event.getX(i) * unitsX));
            int y = clamp(Math.round(event.getY(i) * unitsY));
            int major = 0, minor = 0;
            if (size != null) {
                major = fraction(event.getTouchMajor(i), size[0]);
                minor = size[1] > 0 ? fraction(event.getTouchMinor(i), size[1]) : major;
            }
            int[] s = slotState[slot];
            if (s[1] == x && s[2] == y && s[3] == (major << 16 | minor)) continue;
            s[1] = x;
            s[2] = y;
            s[3] = major << 16 | minor;
            put(buf, EV_ABS, ABS_MT_SLOT, slot);
            put(buf, EV_ABS, ABS_MT_POSITION_X, x);
            put(buf, EV_ABS, ABS_MT_POSITION_Y, y);
            if (size != null) {
                put(buf, EV_ABS, ABS_MT_TOUCH_MAJOR, major);
                put(buf, EV_ABS, ABS_MT_TOUCH_MINOR, minor);
            }
            changed = true;
        }
        if (!changed) return null;
        finish(buf, timestampUs(event));
        return bytes(buf);
    }

    /**
     * Every finger again with a new time, or null when none is down: a touchpad keeps scanning
     * while a finger rests, and a guest that waits for frames to time a hold or age a contact out
     * needs them.
     */
    @Nullable
    public byte[] encodeKeepalive() {
        if (tool == 0) return null;
        var buf = frame();
        for (int slot = 0; slot < TRACKPAD_CONTACTS; slot++) {
            if (slotPointer[slot] == null) continue;
            put(buf, EV_ABS, ABS_MT_SLOT, slot);
            put(buf, EV_ABS, ABS_MT_POSITION_X, slotState[slot][1]);
            put(buf, EV_ABS, ABS_MT_POSITION_Y, slotState[slot][2]);
        }
        finish(buf, SystemClock.uptimeMillis() * 1000L);
        return bytes(buf);
    }

    /** Lifts every finger (the console left the trackpad mid-touch), or null when none is down. */
    @Nullable
    public byte[] encodeRelease() {
        var buf = frame();
        boolean changed = false;
        unslotted.clear();
        for (int slot = 0; slot < TRACKPAD_CONTACTS; slot++) {
            if (slotPointer[slot] == null) continue;
            lift(buf, slot, false);
            changed = true;
        }
        if (!changed) return null;
        finish(buf, SystemClock.uptimeMillis() * 1000L);
        return bytes(buf);
    }

    private void lift(@NonNull ByteBuffer buf, int slot, boolean palm) {
        put(buf, EV_ABS, ABS_MT_SLOT, slot);
        if (palm) put(buf, EV_ABS, ABS_MT_TOOL_TYPE, MT_TOOL_PALM);
        put(buf, EV_ABS, ABS_MT_TRACKING_ID, -1);
        slotPointer[slot] = null;
        slotState[slot][1] = slotState[slot][2] = -1;
        slotState[slot][3] = 0;
    }

    // BTN_TOUCH and the finger count, the single-touch axes on the first finger down, the time.
    private void finish(@NonNull ByteBuffer buf, long timestampUs) {
        int fingers = 0, first = -1;
        for (int slot = 0; slot < TRACKPAD_CONTACTS; slot++) {
            if (slotPointer[slot] == null) continue;
            fingers++;
            if (first < 0) first = slot;
        }
        short now = fingers == 0 ? 0 : TOOLS[fingers - 1];
        if (now != tool) {
            if (tool == 0) put(buf, EV_KEY, BTN_TOUCH, 1);
            if (tool != 0) put(buf, EV_KEY, tool, 0);
            if (now != 0) put(buf, EV_KEY, now, 1);
            if (now == 0) put(buf, EV_KEY, BTN_TOUCH, 0);
            tool = now;
        }
        if (first >= 0) {
            put(buf, EV_ABS, ABS_X, slotState[first][1]);
            put(buf, EV_ABS, ABS_Y, slotState[first][2]);
        }
        // Microseconds, wrapping, as the motion sensors' and hid-multitouch's.
        put(buf, EV_MSC, MSC_TIMESTAMP, (int) timestampUs);
        put(buf, EV_SYN, SYN_REPORT, 0);
    }

    private int freeSlot() {
        for (int slot = 0; slot < TRACKPAD_CONTACTS; slot++)
            if (slotPointer[slot] == null) return slot;
        return -1;
    }

    private int slotOf(int pointerId) {
        for (int slot = 0; slot < TRACKPAD_CONTACTS; slot++)
            if (slotPointer[slot] != null && slotPointer[slot] == pointerId) return slot;
        return -1;
    }

    // The phone panel's touch major and minor maxima for [event]'s device, or null when it reports
    // no contact size (Android gives no AXIS_TOUCH_MAJOR range then, and the sizes read 0).
    @Nullable
    private float[] sizeRange(@NonNull MotionEvent event) {
        int deviceId = event.getDeviceId();
        if (sizeRanges.containsKey(deviceId)) return sizeRanges.get(deviceId);
        float[] range = null;
        var device = InputDevice.getDevice(deviceId);
        if (device != null) {
            var major = device.getMotionRange(MotionEvent.AXIS_TOUCH_MAJOR, event.getSource());
            var minor = device.getMotionRange(MotionEvent.AXIS_TOUCH_MINOR, event.getSource());
            if (major != null && major.getMax() > 0)
                range = new float[]{major.getMax(), minor != null ? minor.getMax() : 0f};
        }
        sizeRanges.put(deviceId, range);
        return range;
    }

    private static int fraction(float value, float max) {
        return Math.max(0, Math.min(TOUCH_SIZE_MAX, Math.round(value / max * TOUCH_SIZE_MAX)));
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(side, v));
    }

    private static long timestampUs(@NonNull MotionEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            return event.getEventTimeNanos() / 1000L;
        return event.getEventTime() * 1000L;
    }

    @NonNull
    private static ByteBuffer frame() {
        // Five fingers' worth of slot, tracking id, tool, position and size, plus the frame's tail.
        return ByteBuffer.allocate(8 * 64).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void put(@NonNull ByteBuffer buf, short type, short code, int value) {
        buf.putShort(type);
        buf.putShort(code);
        buf.putInt(value);
    }

    @NonNull
    private static byte[] bytes(@NonNull ByteBuffer buf) {
        var out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }
}
