package cn.classfun.droidvm.ui.vm.display.base;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.ui.vm.display.base.X11Keymap.*;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class UsbHidInput {
    private static final String TAG = "UsbHidInput";
    private static final int HID_CLASS = UsbConstants.USB_CLASS_HID;
    private static final int PROTO_KEYBOARD = 1;
    private static final int PROTO_MOUSE = 2;
    private static final int REQ_SET_PROTOCOL = 0x0B;
    private static final int REQ_SET_REPORT = 0x09;
    private static final int OUTPUT_REPORT = 0x02;
    private static final int BOOT_PROTOCOL = 0;
    private static final int XK_Pause = 0xFF13;
    private static final int READ_TIMEOUT_MS = 1000;
    private static final String ACTION_USB_PERMISSION = "android.hardware.usb.action.USB_PERMISSION";
    private static final int USB_RECIP_INTERFACE = 0x01;

    public interface Listener {
        void onKeyEvent(int keysym, boolean down);
        void onMouseMove(int dx, int dy);
        void onMouseButton(int button, boolean pressed);
        void onMouseScroll(boolean up);
        void onDeviceChanged(int keyboards, int mice);
    }

    private final Context ctx;
    private final UsbManager usbManager;
    private final Handler main;
    @Nullable
    private volatile Listener listener;
    private volatile boolean running;
    private volatile byte ledState = 0;
    private final Map<String, DeviceReader> readers = new ConcurrentHashMap<>();
    private final Set<String> pendingPerm = ConcurrentHashMap.newKeySet();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, @NonNull Intent intent) {
            var dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            if (dev == null) return;
            var action = intent.getAction();
            if (action != null) switch (action) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    scanDevice(dev);
                    break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    removeDevice(dev.getDeviceName());
                    break;
                case ACTION_USB_PERMISSION:
                    pendingPerm.remove(dev.getDeviceName());
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        openDevice(dev);
                    break;
            }
        }
    };

    public UsbHidInput(@NonNull Context context) {
        ctx = context.getApplicationContext();
        usbManager = ctx.getSystemService(UsbManager.class);
        main = new Handler(Looper.getMainLooper());
    }

    public void setListener(@Nullable Listener l) {
        listener = l;
    }

    public void start() {
        if (running) return;
        running = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        scanAll();
        notifyChanged();
    }

    public void stop() {
        running = false;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        for (var r : readers.values()) r.cancel();
        readers.clear();
        pendingPerm.clear();
    }

    @SuppressWarnings("unused")
    public int getDeviceCount() {
        return readers.size();
    }

    public void setLedState(boolean caps, boolean num, boolean scroll) {
        byte state = 0;
        if (num) state |= 0x01;
        if (caps) state |= 0x02;
        if (scroll) state |= 0x04;
        ledState = state;
        for (var r : readers.values())
            if (r.proto == PROTO_KEYBOARD) r.sendLedReport(state);
    }

    private void scanAll() {
        if (usbManager == null) return;
        for (var dev : usbManager.getDeviceList().values()) scanDevice(dev);
    }

    private void scanDevice(@NonNull UsbDevice device) {
        if (!running) return;
        var name = device.getDeviceName();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            var iface = device.getInterface(i);
            if (iface.getInterfaceClass() != HID_CLASS) continue;
            int proto = iface.getInterfaceProtocol();
            Log.i(TAG, fmt("scanDevice: %s, proto: %d", name, proto));
            if (proto != PROTO_KEYBOARD && proto != PROTO_MOUSE) continue;
            var key = fmt("%s#%d", name, iface.getId());
            if (readers.containsKey(key)) continue;
            if (usbManager.hasPermission(device)) {
                openInterface(device, iface, key);
            } else if (pendingPerm.add(name)) {
                requestPermission(device);
            }
        }
    }

    private void requestPermission(@NonNull UsbDevice device) {
        var intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(ctx.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        var pi = PendingIntent.getBroadcast(ctx, 0, intent, flags);
        usbManager.requestPermission(device, pi);
    }

    private void openDevice(@NonNull UsbDevice device) {
        if (!running) return;
        scanDevice(device);
    }

    private void openInterface(
        @NonNull UsbDevice device,
        @NonNull UsbInterface iface,
        @NonNull String key
    ) {
        int proto = iface.getInterfaceProtocol();
        var conn = usbManager.openDevice(device);
        if (conn == null) {
            Log.e(TAG, fmt("openDevice failed: %s", key));
            return;
        }
        if (!conn.claimInterface(iface, true)) {
            Log.e(TAG, fmt("claimInterface failed: %s", key));
            conn.close();
            return;
        }
        var req = UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS | USB_RECIP_INTERFACE;
        conn.controlTransfer(req, REQ_SET_PROTOCOL, BOOT_PROTOCOL, iface.getId(), null, 0, 200);
        UsbEndpoint epIn = null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            var ep = iface.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                epIn = ep;
                break;
            }
        }
        if (epIn == null) {
            Log.e(TAG, fmt("No IN endpoint: %s", key));
            conn.releaseInterface(iface);
            conn.close();
            return;
        }
        var reader = new DeviceReader(conn, iface, epIn, proto, key);
        readers.put(key, reader);
        reader.start();
        notifyChanged();
    }

    private void removeDevice(@NonNull String deviceName) {
        var prefix = fmt("%s#", deviceName);
        boolean changed = false;
        for (var key : new HashSet<>(readers.keySet())) {
            if (key.startsWith(prefix)) {
                var r = readers.remove(key);
                if (r != null) {
                    r.cancel();
                    changed = true;
                }
            }
        }
        pendingPerm.remove(deviceName);
        if (changed) notifyChanged();
    }

    private void notifyChanged() {
        int kb = 0, ms = 0;
        for (var r : readers.values()) switch (r.proto) {
            case PROTO_KEYBOARD:
                kb++;
                break;
            case PROTO_MOUSE:
                ms++;
                break;
        }
        final int keyboards = kb, mice = ms;
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onDeviceChanged(keyboards, mice);
        });
    }

    static int hidToXKeysym(int hid) {
        if (hid >= 0x04 && hid <= 0x1D) return XK_a + (hid - 0x04);
        if (hid >= 0x1E && hid <= 0x26) return XK_1 + (hid - 0x1E);
        if (hid == 0x27) return XK_0;
        if (hid >= 0x3A && hid <= 0x45) return XK_F1 + (hid - 0x3A);
        if (hid >= 0x59 && hid <= 0x61) return XK_KP_0 + 1 + (hid - 0x59);
        if (hid >= 0x68 && hid <= 0x6F) return XK_F13 + (hid - 0x68);
        if (hid >= 0xE0 && hid <= 0xE7) return hidModifierToXKeysym(hid);
        switch (hid) {
            case 0x28: return XK_Return;
            case 0x29: return XK_Escape;
            case 0x2A: return XK_BackSpace;
            case 0x2B: return XK_Tab;
            case 0x2C: return XK_space;
            case 0x2D: return XK_minus;
            case 0x2E: return XK_equal;
            case 0x2F: return XK_bracketleft;
            case 0x30: return XK_bracketright;
            case 0x31: return XK_backslash;
            case 0x33: return XK_semicolon;
            case 0x34: return XK_apostrophe;
            case 0x35: return XK_grave;
            case 0x36: return XK_comma;
            case 0x37: return XK_period;
            case 0x38: return XK_slash;
            case 0x39: return XK_Caps_Lock;
            case 0x46: return XK_Print;
            case 0x47: return XK_Scroll_Lock;
            case 0x48: return XK_Pause;
            case 0x49: return XK_Insert;
            case 0x4A: return XK_Home;
            case 0x4B: return XK_Page_Up;
            case 0x4C: return XK_Delete;
            case 0x4D: return XK_End;
            case 0x4E: return XK_Page_Down;
            case 0x4F: return XK_Right;
            case 0x50: return XK_Left;
            case 0x51: return XK_Down;
            case 0x52: return XK_Up;
            case 0x53: return XK_Num_Lock;
            case 0x54: return XK_KP_Divide;
            case 0x55: return XK_KP_Multiply;
            case 0x56: return XK_KP_Subtract;
            case 0x57: return XK_KP_Add;
            case 0x58: return XK_KP_Enter;
            case 0x62: return XK_KP_0;
            case 0x63: return XK_KP_Decimal;
            case 0x67: return XK_KP_Equal;
            default: return 0;
        }
    }

    private static int hidModifierToXKeysym(int hid) {
        switch (hid) {
            case 0xE0: return XK_Control_L;
            case 0xE1: return XK_Shift_L;
            case 0xE2: return XK_Alt_L;
            case 0xE3: return XK_Super_L;
            case 0xE4: return XK_Control_R;
            case 0xE5: return XK_Shift_R;
            case 0xE6: return XK_Alt_R;
            case 0xE7: return XK_Super_R;
            default: return 0;
        }
    }

    private final class DeviceReader extends Thread {
        private final UsbDeviceConnection conn;
        private final UsbInterface iface;
        private final UsbEndpoint epIn;
        private final int proto;
        private final String key;
        private volatile boolean alive = true;

        private int prevMods;
        private final Set<Integer> prevKeys = new HashSet<>();
        private int prevButtons;

        DeviceReader(
            UsbDeviceConnection conn, UsbInterface iface,
            UsbEndpoint epIn, int proto, String key
        ) {
            this.conn = conn;
            this.iface = iface;
            this.epIn = epIn;
            this.proto = proto;
            this.key = key;
            setName(fmt("UsbHid-%s", proto == PROTO_KEYBOARD ? "Kbd" : "Mouse"));
            setDaemon(true);
        }

        void cancel() {
            alive = false;
            interrupt();
        }

        void sendLedReport(byte state) {
            var req = UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_CLASS
                | USB_RECIP_INTERFACE;
            conn.controlTransfer(req, REQ_SET_REPORT,
                (OUTPUT_REPORT << 8), iface.getId(),
                new byte[]{state}, 1, 200);
        }

        @Override
        public void run() {
            int max = Math.max(8, epIn.getMaxPacketSize());
            byte[] buf = new byte[max];
            Log.i(TAG, fmt("Reader started: %s", key));
            if (proto == PROTO_KEYBOARD) sendLedReport(ledState);
            while (alive) {
                int n;
                try {
                    n = conn.bulkTransfer(epIn, buf, buf.length, READ_TIMEOUT_MS);
                } catch (Exception e) {
                    if (!alive) break;
                    Log.w(TAG, fmt("bulkTransfer error: %s", e.getMessage()));
                    break;
                }
                if (n < 0) {
                    if (!alive) break;
                    continue;
                }
                if (n == 0) continue;
                if (proto == PROTO_KEYBOARD) parseKeyboard(buf, n);
                else parseMouse(buf, n);
            }
            try {
                conn.releaseInterface(iface);
            } catch (Exception ignored) {
            }
            try {
                conn.close();
            } catch (Exception ignored) {
            }
            if (readers.remove(key) != null) notifyChanged();
            Log.i(TAG, fmt("Reader stopped: %s", key));
        }

        private void parseKeyboard(byte[] d, int len) {
            if (len < 3) return;
            int mods = d[0] & 0xFF;
            int modDiff = mods ^ prevMods;
            if (modDiff != 0) {
                for (int b = 0; b < 8; b++) {
                    if ((modDiff & (1 << b)) == 0) continue;
                    boolean down = (mods & (1 << b)) != 0;
                    int keysym = hidToXKeysym(0xE0 + b);
                    if (keysym != 0) dispatchKey(keysym, down);
                }
                prevMods = mods;
            }
            var cur = new HashSet<Integer>();
            int keyEnd = Math.min(len, 8);
            for (int i = 2; i < keyEnd; i++) {
                int code = d[i] & 0xFF;
                if (code != 0) cur.add(code);
            }
            for (int code : cur) {
                if (!prevKeys.contains(code)) {
                    int ks = hidToXKeysym(code);
                    if (ks != 0) dispatchKey(ks, true);
                }
            }
            for (int code : prevKeys) {
                if (!cur.contains(code)) {
                    int ks = hidToXKeysym(code);
                    if (ks != 0) dispatchKey(ks, false);
                }
            }
            prevKeys.clear();
            prevKeys.addAll(cur);
        }

        private void parseMouse(byte[] d, int len) {
            if (len < 3) return;
            int buttons = d[0] & 0x07;
            int dx = d[1];
            int dy = d[2];
            int diff = buttons ^ prevButtons;
            if (diff != 0) {
                for (int b = 0; b < 3; b++) {
                    if ((diff & (1 << b)) == 0) continue;
                    dispatchMouseButton(b, (buttons & (1 << b)) != 0);
                }
                prevButtons = buttons;
            }
            if (dx != 0 || dy != 0) dispatchMouseMove(dx, dy);
            if (len >= 4) {
                int wheel = d[3];
                if (wheel != 0) dispatchScroll(wheel > 0);
            }
        }

        private void dispatchKey(int keysym, boolean down) {
            Listener l = listener;
            if (l != null) l.onKeyEvent(keysym, down);
        }

        private void dispatchMouseMove(int dx, int dy) {
            Listener l = listener;
            if (l != null) l.onMouseMove(dx, dy);
        }

        private void dispatchMouseButton(int button, boolean pressed) {
            Listener l = listener;
            if (l != null) l.onMouseButton(button, pressed);
        }

        private void dispatchScroll(boolean up) {
            Listener l = listener;
            if (l != null) l.onMouseScroll(up);
        }
    }
}
