// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.EvdevEncoder;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;

/** Android capture -> per-device translation -> ordered evdev frames -> existing VM sink. */
public final class StandardPointerCapture implements CapturedPointerLayout.Handler, InputManager.InputDeviceListener {
    private static final String TAG = "StandardPointerCapture";
    // Calibrate continuous relative touchpad deltas before emitting discrete guest wheel steps.
    private static final float TOUCHPAD_SCROLL_UNITS_PER_NOTCH = 32f;
    private final CapturedPointerLayout view;
    private final InputManager inputManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable acquire = this::start;
    private final SparseArray<DeviceState> devices = new SparseArray<>();
    // Reuse one worker across sink replacement so old releases cannot arrive after new presses.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "CapturedPointer"));
    private PointerEventQueue queue;
    private PointerButtons buttons;
    private boolean enabled, resumed, focused;
    private boolean active, natural, systemNatural, closed;

    public StandardPointerCapture(CapturedPointerLayout view) {
        this.view = view;
        Context context = view.getContext();
        inputManager = context.getSystemService(InputManager.class);
        view.setCaptureHandler(this);
    }
    public boolean isEnabled() { return enabled; }
    private SharedPreferences preferences() {
        return view.getContext().getSharedPreferences("pointer-grab", Context.MODE_PRIVATE);
    }
    public boolean isNaturalScroll() { return preferences().getBoolean("natural_scroll", true); }
    public void setNaturalScroll(boolean value) {
        if (value == isNaturalScroll()) return;
        preferences().edit().putBoolean("natural_scroll", value).apply();
        updateCapture();
    }
    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        updateCapture();
    }
    public void setResumed(boolean value) {
        if (resumed == value) return;
        resumed = value;
        updateCapture();
    }
    public void setFocused(boolean value) {
        if (focused == value) return;
        focused = value;
        updateCapture();
    }
    private boolean ready() {
        return !closed && enabled && resumed && focused && queue != null;
    }
    private void updateCapture() {
        main.removeCallbacks(acquire);
        if (ready()) main.post(acquire); // Let menu and window focus callbacks finish first.
        else stop();
    }
    public void setSink(InputForwarder.InputSink sink) {
        if (closed) return;
        main.removeCallbacks(acquire);
        stop();
        if (queue != null) queue.close();
        queue = null; buttons = null;
        if (sink == null) return;
        queue = new PointerEventQueue(worker, new PointerEventQueue.Output() {
            private void write(byte[] data) {
                if (data == null) return;
                try {
                    if (!sink.write(NativeDisplay.MOUSE, data)) Log.w(TAG, "Pointer frame was not delivered");
                } catch (RuntimeException error) { Log.e(TAG, "Pointer write failed", error); }
            }
            public void move(int x, int y) { write(EvdevEncoder.encodeMouseMove(x, y)); }
            public void button(int code, boolean down) { write(EvdevEncoder.encodeMouseButton((short) code, down)); }
            public void scroll(int v, int h) { write(EvdevEncoder.encodeMouseWheel(v, h)); }
            public void failed(RuntimeException error) { Log.e(TAG, "Pointer encoding failed", error); }
        });
        buttons = new PointerButtons(new PointerEventQueue.Output() {
            public void move(int x, int y) { queue.move(x, y); }
            public void button(int code, boolean down) { queue.button(code, down); }
            public void scroll(int v, int h) { queue.scroll(v, h); }
        });
        updateCapture();
    }
    private void start() {
        if (!ready() || !view.hasWindowFocus()) return;
        boolean natural = isNaturalScroll();
        if (this.natural != natural) resetDevices();
        this.natural = natural;
        systemNatural = readSystemNaturalScroll();
        if (active && view.hasPointerCapture()) return;
        if (!view.hasFocus() && !view.requestFocus()) return;
        if (!active && inputManager != null)
            inputManager.registerInputDeviceListener(this, main);
        active = true;
        view.requestPointerCapture();
    }
    private void stop() {
        if (active && inputManager != null) inputManager.unregisterInputDeviceListener(this);
        boolean requested = active;
        active = false;
        if (requested || view.hasPointerCapture()) view.releasePointerCapture();
        resetDevices();
    }
    public void close() { close(null); }
    /** Complete cleanup on the pointer worker after all queued releases have drained. */
    public void close(Runnable afterDrain) {
        if (closed) return;
        setSink(null); closed = true;
        if (afterDrain != null) worker.execute(afterDrain);
        worker.shutdown(); // drain the old sink's releases before retiring the worker
        view.setCaptureHandler(null);
    }
    public void requestAfterClick(MotionEvent event) {
        if (ready() && active && !view.hasPointerCapture() && (event.isFromSource(InputDevice.SOURCE_MOUSE)
                || event.isFromSource(InputDevice.SOURCE_TOUCHPAD))) {
            if (!view.hasFocus()) view.requestFocus();
            view.requestPointerCapture();
        }
    }
    @Override public void onCaptureChanged(boolean captured) {
        if (!captured) resetDevices();
        Log.i(TAG, captured ? "Android pointer capture acquired" : "Android pointer capture released");
    }
    private void resetDevices() {
        for (int i = 0; i < devices.size(); i++) devices.valueAt(i).reset();
        devices.clear();
        if (buttons != null) buttons.reset();
    }
    @Override public void onInputDeviceAdded(int id) { }
    @Override public void onInputDeviceChanged(int id) { removeDevice(id); }
    @Override public void onInputDeviceRemoved(int id) { removeDevice(id); }
    private void removeDevice(int id) {
        DeviceState device = devices.get(id);
        if (device != null) { device.reset(); devices.remove(id); }
        if (buttons != null) buttons.set(id, 0);
    }
    @Override public boolean onEvent(MotionEvent event) {
        if (!active || queue == null) return false;
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                && !event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                && !event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        int id = event.getDeviceId();
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { removeDevice(id); return true; }
        boolean raw = event.isFromSource(InputDevice.SOURCE_TOUCHPAD);
        DeviceState state = devices.get(id);
        // Sources can change when Android switches its touchpad capture mode.
        if (state != null && state.raw != raw) { removeDevice(id); state = null; }
        if (state == null) {
            state = new DeviceState(id, event.getDevice(), raw);
            devices.put(id, state);
            Log.i(TAG, String.format("captured %s source=0x%x device=%s",
                raw ? "contacts" : state.touchpad ? "touchpad gestures" : "mouse",
                event.getSource(), event.getDevice() == null ? "unknown" : event.getDevice().getName()));
        }
        if (raw) state.contacts(event);
        else state.relative(event);
        return true;
    }
    private boolean readSystemNaturalScroll() {
        try {
            return Settings.System.getInt(view.getContext().getContentResolver(), "touchpad_natural_scrolling", 1) != 0;
        } catch (SecurityException ignored) { return true; }
    }
    private final class DeviceState implements PointerEventQueue.Output {
        final int id;
        final boolean raw, touchpad;
        final TouchpadGestureRecognizer pad;
        final float xMin, yMin, xResolution, yResolution;
        float moveX, moveY, wheelV, wheelH, hoverX, hoverY;
        boolean hoverValid;
        int held, rawButtons;
        DeviceState(int id, InputDevice device, boolean raw) {
            this.id = id; this.raw = raw;
            touchpad = raw || device != null && device.supportsSource(InputDevice.SOURCE_TOUCHPAD);
            pad = raw ? new TouchpadGestureRecognizer(this, natural) : null;
            InputDevice.MotionRange x = range(device, MotionEvent.AXIS_X);
            InputDevice.MotionRange y = range(device, MotionEvent.AXIS_Y);
            xMin = x == null ? 0 : x.getMin(); yMin = y == null ? 0 : y.getMin();
            xResolution = resolution(x, 100); yResolution = resolution(y, 60);
        }
        public void move(int x, int y) { queue.move(x, y); }
        public void scroll(int v, int h) { queue.scroll(v, h); }
        public void button(int code, boolean down) {
            int bit = 1 << (code - 0x110);
            held = down ? held | bit : held & ~bit;
            buttons.set(id, held);
        }
        void move(float x, float y) {
            moveX += x; moveY += y;
            int dx = (int) moveX, dy = (int) moveY;
            moveX -= dx; moveY -= dy;
            queue.move(dx, dy);
        }
        void relative(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
                for (int h = 0; h < event.getHistorySize(); h++)
                    move(event.getHistoricalX(h), event.getHistoricalY(h));
                move(event.getX(), event.getY());
            }
            if (action == MotionEvent.ACTION_SCROLL) wheel(event);
            held = buttonState(event) & 31;
            buttons.set(id, held);
        }
        void wheel(MotionEvent event) {
            // Relative touchpad gestures have already had the system direction applied.
            // A normal mouse wheel must never inherit the touchpad preference.
            float sign = !touchpad ? 1 : raw ? (natural ? -1 : 1)
                : natural == systemNatural ? 1 : -1;
            float scale = touchpad && !raw ? sign / TOUCHPAD_SCROLL_UNITS_PER_NOTCH : sign;
            for (int h = 0; h <= event.getHistorySize(); h++) {
                boolean current = h == event.getHistorySize();
                wheelV += (current ? event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    : event.getHistoricalAxisValue(MotionEvent.AXIS_VSCROLL, h)) * scale;
                wheelH += (current ? event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    : event.getHistoricalAxisValue(MotionEvent.AXIS_HSCROLL, h)) * scale;
            }
            int v = (int) wheelV, h = (int) wheelH;
            wheelV -= v; wheelH -= h;
            queue.scroll(v, h);
        }
        void contacts(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_SCROLL) {
                pad.cancelTap(); wheel(event); setRawButtons(event.getButtonState()); hoverValid = false;
            } else if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
                setRawButtons(buttonState(event));
            } else if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER
                    || action == MotionEvent.ACTION_HOVER_EXIT) {
                for (int h = 0; h < event.getHistorySize(); h++) hover(event, h);
                hover(event, -1);
                if (action == MotionEvent.ACTION_HOVER_EXIT) hoverValid = false;
                setRawButtons(event.getButtonState());
            } else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_MOVE) {
                hoverValid = false;
                for (int h = 0; h < event.getHistorySize(); h++) sample(event, h);
                sample(event, -1);
            }
        }
        void setRawButtons(int next) { rawButtons = next; pad.setButtons(next); }
        void hover(MotionEvent event, int history) {
            float x = ((history < 0 ? event.getX() : event.getHistoricalX(history)) - xMin) / xResolution;
            float y = ((history < 0 ? event.getY() : event.getHistoricalY(history)) - yMin) / yResolution;
            float dx = history < 0 ? event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                : event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, history);
            float dy = history < 0 ? event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                : event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, history);
            if (dx != 0 || dy != 0) move(dx, dy);
            else if (hoverValid) move((x - hoverX) * 12, (y - hoverY) * 12);
            hoverX = x; hoverY = y; hoverValid = true;
        }
        void sample(MotionEvent event, int history) {
            int action = event.getActionMasked();
            int up = history < 0 && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                ? event.getActionIndex() : -1;
            long fingers = 0;
            float x = 0, y = 0;
            int count = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                int tool = event.getToolType(i);
                if (i == up || tool != MotionEvent.TOOL_TYPE_FINGER && tool != MotionEvent.TOOL_TYPE_UNKNOWN) continue;
                fingers |= 1L << event.getPointerId(i); count++;
                x += ((history < 0 ? event.getX(i) : event.getHistoricalX(i, history)) - xMin) / xResolution;
                y += ((history < 0 ? event.getY(i) : event.getHistoricalY(i, history)) - yMin) / yResolution;
            }
            pad.frame(fingers, count == 0 ? 0 : x / count, count == 0 ? 0 : y / count,
                history < 0 ? event.getEventTime() : event.getHistoricalEventTime(history),
                history < 0 ? event.getButtonState() : rawButtons);
            if (history < 0) rawButtons = event.getButtonState();
        }
        void reset() {
            if (pad != null) pad.reset();
            held = 0; buttons.set(id, 0);
        }
    }
    private static int buttonState(MotionEvent event) {
        // The action button can be newer than the reported mask on press and release.
        int buttons = event.getButtonState();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_BUTTON_PRESS) return buttons | event.getActionButton();
        if (action == MotionEvent.ACTION_BUTTON_RELEASE) return buttons & ~event.getActionButton();
        return buttons;
    }
    private static InputDevice.MotionRange range(InputDevice device, int axis) {
        if (device == null) return null;
        InputDevice.MotionRange range = device.getMotionRange(axis, InputDevice.SOURCE_TOUCHPAD);
        return range == null ? device.getMotionRange(axis) : range;
    }
    private static float resolution(InputDevice.MotionRange range, float fallbackSizeMm) {
        if (range == null) return 1;
        if (range.getResolution() > 0) return range.getResolution();
        // Android does not require physical dimensions. Use a generic nominal pad size only
        // when the driver omits resolution; never match a vendor, product, or device name.
        return range.getRange() > 0 ? range.getRange() / fallbackSizeMm : 1;
    }
}
