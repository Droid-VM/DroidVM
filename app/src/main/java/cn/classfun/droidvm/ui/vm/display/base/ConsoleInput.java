// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.util.function.Supplier;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.DirectInputSink;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.TrackpadEncoder;

/**
 * The input both consoles -- native and VNC -- send the same way: the pointer mode (one setting
 * for the app) and what follows it, the path to the VM (the daemon's socket once its binder is
 * here, the vm_input request until then and whenever a write fails), the hardware mouse's right
 * and middle buttons kept from turning into BACK, and the trackpad's fingers.
 *
 * <p>Where a touch lands on the picture is each console's own -- a turned SurfaceView on one, a
 * zoomable image and RFB's tablet on the other -- and stays there.</p>
 */
public final class ConsoleInput {
    private static final String PREFS = "droidvm_prefs";
    private static final String KEY_INPUT_MODE = "display_input_mode";
    // How long after a mouse's right or middle press a BACK is taken for the press, not a key.
    private static final long MOUSE_BACK_SUPPRESS_MS = 800;

    private final Context context;
    private final SharedPreferences prefs;
    private final String vmId;
    private final Supplier<String> screenId;
    private final boolean screenInputEnabled;
    private InputMode mode;
    @Nullable
    private ConsoleWorkspace workspace;
    @Nullable
    private InputForwarder forwarder;
    @Nullable
    private PointerGestureTranslator translator;
    @Nullable
    private volatile DirectInputSink direct;
    private long lastMouseButtonMs;

    /**
     * @param screenInputEnabled this screen's input switch: off, the absolute modes ride devices
     *                           the screen does not have
     */
    public ConsoleInput(@NonNull Context context, @NonNull String vmId,
                        @NonNull Supplier<String> screenId, boolean screenInputEnabled) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.vmId = vmId;
        this.screenId = screenId;
        this.screenInputEnabled = screenInputEnabled;
        this.mode = InputMode.fromOrdinal(prefs.getInt(KEY_INPUT_MODE, 0));
    }

    @NonNull
    public InputMode mode() {
        return mode;
    }

    /** The workspace whose mouse buttons follow the mode; told the mode at once. */
    public void setWorkspace(@NonNull ConsoleWorkspace workspace) {
        this.workspace = workspace;
        workspace.setInputMode(mode);
    }

    /** The gesture translator, absolute in TABLET; told the mode at once. */
    public void setTranslator(@NonNull PointerGestureTranslator translator) {
        this.translator = translator;
        translator.setAbsolute(mode == InputMode.TABLET);
    }

    /**
     * Selects [mode] and keeps it as the app's setting; false when it already was. With this
     * screen's input off, the absolute modes are selectable and inert -- TOUCH and TABLET ride the
     * screen's own devices (on VNC the switch is a view-only binding, whose RFB pointer and keys
     * crosvm drops), MOUSE and TRACKPAD are the VM's -- so that is said once, here, rather than
     * leaving the user tapping a screen that answers nothing.
     */
    public boolean setMode(@NonNull InputMode mode) {
        if (this.mode == mode) return false;
        if (!screenInputEnabled && !mode.isVmPointer())
            Toast.makeText(context, R.string.display_input_disabled_hint, Toast.LENGTH_LONG).show();
        this.mode = mode;
        prefs.edit().putInt(KEY_INPUT_MODE, mode.ordinal()).apply();
        if (workspace != null) workspace.setInputMode(mode);
        if (forwarder != null) forwarder.setInputMode(mode);
        if (translator != null) {
            translator.setAbsolute(mode == InputMode.TABLET);
            translator.reset();
        }
        return true;
    }

    /**
     * A forwarder to the VM, in the current mode: through the daemon's socket while attached,
     * the vm_input request otherwise. The latest one follows the mode and carries the mouse
     * buttons.
     */
    @NonNull
    public InputForwarder newForwarder() {
        var created = new InputForwarder((channel, data) -> {
            var sink = direct;
            return sink != null ? sink.write(channel, data) : sendToDaemon(channel, data);
        }, TrackpadEncoder.sideUnits(context));
        created.setInputMode(mode);
        forwarder = created;
        return created;
    }

    /** The daemon's binder is here: writes go to its socket, falling back per write. */
    public void attach(@NonNull INativeDisplayRootService service) {
        var old = direct;
        direct = new DirectInputSink(vmId, screenId, service, this::sendToDaemon);
        if (old != null) old.close();
    }

    /** Writes go back to the vm_input request. */
    public void detach() {
        var old = direct;
        direct = null;
        if (old != null) old.close();
    }

    /** The console is going: the forwarder stops and the socket path is dropped. */
    public void close() {
        if (forwarder != null) {
            forwarder.close();
            forwarder = null;
        }
        detach();
    }

    /** A mouse button from the console's own buttons: EvdevEncoder.BTN_LEFT or BTN_RIGHT. */
    public void sendPointerButton(short button, boolean down) {
        if (forwarder != null) forwarder.sendPointerButton(button, down);
    }

    // The vm_input request: the daemon owns crosvm's input sockets and writes the records to the
    // guest. Called on the forwarder's (single) worker thread, so the synchronous request keeps
    // events ordered and back-pressured.
    private boolean sendToDaemon(int channel, @NonNull byte[] data) {
        try {
            var req = new JSONObject();
            req.put("command", "vm_input");
            req.put("vm_id", vmId);
            req.put("screen", screenId.get());
            req.put("channel", channel);
            req.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
            var resp = DaemonConnection.getInstance().request(req);
            return resp.optBoolean("delivered", false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A hardware mouse's right or middle press on the touch stream (ACTION_DOWN with the button
     * held): the generic-motion path delivers the button, so the touch path takes it and does
     * nothing -- else a right click doubles as a left tap -- and an unhandled one is what the
     * framework turns into BACK.
     */
    public boolean isMouseButtonTouch(@NonNull MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_MOUSE) != 0
            && (event.getButtonState() & (MotionEvent.BUTTON_SECONDARY
                | MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
            noteMouseButton();
            return true;
        }
        return false;
    }

    /** A mouse button went to the VM: a BACK right after it is the button's, not a key. */
    public void noteMouseButton() {
        lastMouseButtonMs = SystemClock.uptimeMillis();
    }

    /**
     * A BACK that is a mouse's right click the framework (or an OEM ROM) did not see consumed:
     * mouse-sourced, or -- injected claiming a keyboard or virtual source -- right after a mouse
     * button. The click itself already went to the VM.
     */
    public boolean swallowsBack(@NonNull KeyEvent event) {
        return event.getKeyCode() == KeyEvent.KEYCODE_BACK
            && ((event.getSource() & InputDevice.SOURCE_MOUSE) != 0
                || SystemClock.uptimeMillis() - lastMouseButtonMs < MOUSE_BACK_SUPPRESS_MS);
    }

    /** Fingers on the phone's screen, not a hardware mouse driving the touch stream. */
    public static boolean isFingerTouch(@NonNull MotionEvent event) {
        return (event.getSource() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN;
    }

    /**
     * TRACKPAD: the whole pointer area is the VM's trackpad, the fingers where they are on it in
     * millimetres -- no picture to map to, the guest moves its own pointer.
     */
    public boolean sendTrackpadTouch(@NonNull MotionEvent event) {
        if (forwarder == null) return false;
        float units = TrackpadEncoder.unitsPerPx(context);
        forwarder.sendTouchEvent(event, units, units);
        return true;
    }

    /**
     * Puts the system keyboard away. Dropping [focus]'s focus first matters: it is what the IME
     * is attached to, and some ROMs show the keyboard again for a still-focused view right after
     * a hide request.
     */
    public static void hideSoftKeyboard(@NonNull Activity activity, @NonNull View focus) {
        focus.clearFocus();
        WindowCompat.getInsetsController(activity.getWindow(), focus)
            .hide(WindowInsetsCompat.Type.ime());
        var imm = activity.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }
}
