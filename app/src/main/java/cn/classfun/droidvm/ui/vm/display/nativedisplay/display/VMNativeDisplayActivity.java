// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static android.view.Gravity.CENTER;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.lib.ui.DragTouchListener;
import cn.classfun.droidvm.lib.ui.ImeInsetsExempt;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.vm.display.base.DaemonDisplayAttach;
import cn.classfun.droidvm.ui.vm.display.base.DisplayChromeController;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.base.DisplayKeyboardMenuRow;
import cn.classfun.droidvm.ui.vm.display.base.KeyboardMode;
import cn.classfun.droidvm.ui.vm.display.base.DisplayPhysicalKeyboardView;
import cn.classfun.droidvm.ui.vm.display.base.DisplaySource;
import cn.classfun.droidvm.ui.vm.display.base.DisplayViewportController;
import cn.classfun.droidvm.ui.vm.display.base.InputMode;
import cn.classfun.droidvm.ui.vm.display.base.PointerGestureTranslator;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.EvdevEncoder;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.DirectInputSink;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;
import cn.classfun.droidvm.lib.perf.GamePerfHint;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.NativeExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.NativeKeyboardEditText;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.TouchScaleCalculator;

/**
 * Native display: shows a VM's gfxstream/virtio-gpu output by handing crosvm an Android Surface
 * (via the per-VM ICrosvmAndroidDisplayService binder) and forwarding touch/keyboard as evdev.
 *
 * Input: the daemon (uid=0) owns crosvm's --input sockets (it binds them before crosvm starts and
 * accepts the connection), so this Activity forwards evdev to the daemon rather than writing the
 * sockets directly. The daemon hosts a broker binder ({@link INativeDisplayRootService}) that both
 * looks up the per-VM display binder and, on the touch hot path, writes evdev straight to crosvm
 * ({@link DirectInputSink}); the vm_input IPC command is the fallback when that path is unavailable.
 * The binder can't ride the daemon's TCP/JSON-RPC channel, so it arrives via a broadcast the daemon
 * sends in response to the {@code display_attach} request below.
 */
// ImeInsetsExempt: the display area handles the IME inset itself (root insets listener below);
// without the exemption the app-wide ImeInsetsApplier would pad the content view a second time.
public final class VMNativeDisplayActivity extends AppCompatActivity implements ImeInsetsExempt {
    private static final String TAG = "VMNativeDisplay";
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_VM_ID = "vm_id";
    public static final String EXTRA_WIDTH = "display_width";
    public static final String EXTRA_HEIGHT = "display_height";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Converts committed IME text into key events (handles Shift for upper-case/symbols).
    private final KeyCharacterMap keyCharacterMap =
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    private MaterialToolbar toolbar;
    private LinearLayout statusBar;
    private View statusIndicator;
    private TextView tvStatus;
    private LinearLayout overlayConnecting;
    private TextView tvConnectingMessage;
    private FrameLayout displayContainer;
    private SurfaceView surfaceView;
    private SurfaceView cursorView;

    // Last viewport transform, mirrored so the cursor overlay can be placed without asking the
    // controller to recompute it. Written only by onViewportChanged, read only on the main thread.
    private float vpBaseW, vpBaseH, vpViewScale = 1f, vpOffsetX, vpOffsetY;

    // True while a finger is on the container in relative-pointer mode. This is the gate for
    // viewport follow: a cursor move from a REMOTE DESKTOP session is byte-identical to one the
    // user made -- the position stream cannot tell them apart -- so only the input layer, which
    // knows whether this device is currently driving, may decide to pan.
    private boolean pointerDriveActive = false;
    private int lastCursorX = -1;
    private int lastCursorY = -1;
    private NativeKeyboardEditText keyboardInput;
    private FloatingActionButton fabMenu;
    private MaterialButton btnFullscreen;
    private DisplayExtraKeysPanel extraKeysPanel;
    private DisplayPhysicalKeyboardView phyKeyboard;

    private String vmName = "";
    private String vmId = "";
    private String vmKey = "";
    private int guestWidth = 1280;
    private int guestHeight = 720;

    private DisplaySource displaySource;
    private InputForwarder inputForwarder;
    private DirectInputSink directSink;
    private NativeExtraKeysPanel nativeExtraKeys;
    private boolean connected = false;
    // Pointer input mode, shared with the VNC path via the "display_input_mode" pref; applied to the
    // InputForwarder so on-screen touches route to the multi-touch / mouse / tablet virtio device.
    private InputMode inputMode = InputMode.TOUCH;
    private static final String INPUT_PREFS = "droidvm_prefs";
    private static final String KEY_INPUT_MODE = "display_input_mode";
    // Chrome memory, shared with the VNC path: extra-keys on/off per typing surface + whether
    // the physical keyboard is up.
    private static final String KEY_KEYBOARD_MODE = "display_keyboard_mode";
    private static final String KEY_ZONE_EXTRA = "display_keyboard_zone_extra";
    private static final String KEY_ZONE_FNX = "display_keyboard_zone_fnx";
    // Unified MOUSE/TABLET gesture layer (two-finger tap = right click, two-finger pan = scroll,
    // three-finger = local zoom/pan). TOUCH mode bypasses it and stays raw multi-touch.
    private PointerGestureTranslator gestureTranslator;
    // Fractional remainders of relative mouse motion so slow drags aren't rounded away.
    private float mouseRemX, mouseRemY;
    // Last mouse right/middle-button activity: any BACK arriving shortly after is the framework's
    // (or OEM's) right-click fallback, regardless of what source it claims - swallow it.
    private long lastMouseButtonMs;
    private static final long MOUSE_BACK_SUPPRESS_MS = 800;
    // Single sources of truth for viewport geometry (fit/zoom/pan across display-area changes)
    // and chrome visibility (fullscreen / extra keys). See the controller classes for the rules.
    private DisplayViewportController viewport;
    private DisplayChromeController chrome;
    // Display areas smaller than this (e.g. landscape with a tall IME) freeze the viewport
    // instead of re-laying it out; see DisplayViewportController.
    private static final int MIN_AREA_DP = 96;
    /** Screen-space breathing room kept around the guest cursor when the view follows it. */
    private static final float CURSOR_FOLLOW_MARGIN_PX = 96f;

    // Maps the unified gestures onto the crosvm --input evdev channels via InputForwarder.
    private final PointerGestureTranslator.Listener gestureListener =
        new PointerGestureTranslator.Listener() {
            @Override
            public void onRelativeMove(float dxGuest, float dyGuest) {
                if (inputForwarder == null) return;
                mouseRemX += dxGuest;
                mouseRemY += dyGuest;
                int dx = (int) mouseRemX, dy = (int) mouseRemY;
                if (dx == 0 && dy == 0) return;
                mouseRemX -= dx;
                mouseRemY -= dy;
                inputForwarder.sendMouseMove(dx, dy);
            }

            @Override
            public void onAbsoluteMove(float xGuest, float yGuest) {
                if (inputForwarder != null)
                    inputForwarder.sendAbsMove(Math.round(xGuest), Math.round(yGuest));
            }

            @Override
            public void onLeftButton(boolean down, float xGuest, float yGuest) {
                if (inputForwarder == null) return;
                if (inputMode == InputMode.TABLET) {
                    inputForwarder.sendAbsLeftButton(down, Math.round(xGuest), Math.round(yGuest));
                } else {
                    inputForwarder.sendPointerButton(EvdevEncoder.BTN_LEFT, down);
                }
            }

            @Override
            public void onLeftTap(float xGuest, float yGuest) {
                onLeftButton(true, xGuest, yGuest);
                onLeftButton(false, xGuest, yGuest);
            }

            @Override
            public void onRightClick(float xGuest, float yGuest) {
                if (inputForwarder == null) return;
                if (inputMode == InputMode.TABLET)
                    inputForwarder.sendAbsMove(Math.round(xGuest), Math.round(yGuest));
                inputForwarder.sendPointerButton(EvdevEncoder.BTN_RIGHT, true);
                inputForwarder.sendPointerButton(EvdevEncoder.BTN_RIGHT, false);
            }

            @Override
            public void onScroll(int vNotches, int hNotches) {
                if (inputForwarder != null) inputForwarder.sendScroll(vNotches, hNotches);
            }

            @Override
            public void onZoomPan(float scaleFactor, float dxView, float dyView,
                                  float focusX, float focusY) {
                if (viewport != null) viewport.onZoomPan(scaleFactor, dxView, dyView);
            }
        };

    // Daemon broker binder acquisition (display_attach -> nonce-matched broadcast), shared with
    // the VNC display path.
    private DaemonDisplayAttach displayAttach;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_vm_native_display);

        var intent = getIntent();
        vmName = orEmpty(intent.getStringExtra(EXTRA_VM_NAME));
        vmId = orEmpty(intent.getStringExtra(EXTRA_VM_ID));
        guestWidth = (int) intent.getLongExtra(EXTRA_WIDTH, 1280);
        guestHeight = (int) intent.getLongExtra(EXTRA_HEIGHT, 720);
        vmKey = NativeDisplay.serviceNameFromId(vmId);

        bindViews();
        toolbar.setTitle(vmName.isEmpty() ? getString(R.string.native_display_title) : vmName);
        toolbar.setNavigationOnClickListener(v -> finish());
        setupViews();
        setupLayoutControllers();
        setStatus(getString(R.string.native_display_connecting), R.color.vnc_status_connecting);
        showOverlay(getString(R.string.native_display_waiting));

        if (vmId.isEmpty()) {
            setStatus(getString(R.string.native_display_failed), R.color.vnc_status_error);
            showOverlay(getString(R.string.native_display_failed));
            return;
        }
        displayAttach = new DaemonDisplayAttach(this, mainHandler,
            new DaemonDisplayAttach.Listener() {
                @Override
                public void onAttached(@NonNull INativeDisplayRootService service) {
                    onRootConnected(service);
                }

                @Override
                public void onLost() {
                    // DirectInputSink falls back to the vm_input RPC per write on a dead binder.
                }
            });
        displayAttach.start();
    }

    private void onRootConnected(@NonNull INativeDisplayRootService service) {
        // Try a direct unix-socket sink to the daemon (one write per evdev frame, no IPC
        // round-trip); on any failure it falls back to the vm_input JSON-RPC path below.
        directSink = new DirectInputSink(vmId, service, this::sendInputToDaemon);
        inputForwarder = new InputForwarder(directSink);
        if (nativeExtraKeys != null) nativeExtraKeys.setForwarder(inputForwarder);
        // A forwarder is built fresh on every attach and starts in TOUCH, so it has to be told
        // the mode this session is actually in (restored in setupViews, or since changed).
        inputForwarder.setInputMode(inputMode);

        // Start the VM now that listeners are up (no-op if already running).
        DaemonConnection.getInstance().buildRequest("vm_start")
            .put("vm_id", vmId)
            .onResponse(r -> {})
            .onUnsuccessful(r -> {})
            .onError(e -> Log.w(TAG, "vm_start request failed", e))
            .invoke();

        // Display binder is looked up via the root service (servicemanager) on a bg thread.
        displaySource = new NativeSurfaceSource(
            surfaceView, guestWidth, guestHeight,
            () -> {
                var svc = displayAttach.getService();
        // Hardware cursor: give crosvm a Surface for the guest's cursor plane and follow the
        // positions it reports. Both are no-ops on a guest that never uses the cursor plane.
        displaySource.setCursorView(cursorView);
        displaySource.setCursorListener(this::onGuestCursorMoved);
                if (svc == null) return null;
                try {
                    return svc.waitForDisplayBinder(vmKey);
                } catch (Exception e) {
                    return null;
                }
            },
            mainHandler,
            new DisplaySource.Callbacks() {
                @Override
                public void onContentSize(int width, int height) {
                    guestWidth = width;
                    guestHeight = height;
                    viewport.setContentSize(width, height);
                    // Keep the status-bar resolution label in step with a live resize.
                    if (connected)
                        setStatus(fmt(getString(R.string.native_display_connected),
                            guestWidth, guestHeight), R.color.vnc_status_connected);
                }

                @Override
                public void onStateChanged(@NonNull DisplaySource.State state) {
                    onDisplayStateChanged(state);
                }
            });
        displaySource.start();
    }

    private void onDisplayStateChanged(@NonNull DisplaySource.State state) {
        connected = state == DisplaySource.State.CONNECTED;
        if (connected) {
            setStatus(fmt(getString(R.string.native_display_connected), guestWidth, guestHeight),
                R.color.vnc_status_connected);
            hideOverlay();
        } else {
            setStatus(getString(R.string.native_display_connecting), R.color.vnc_status_connecting);
            showOverlay(getString(R.string.native_display_waiting));
        }
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar);
        statusBar = findViewById(R.id.status_bar);
        statusIndicator = findViewById(R.id.status_indicator);
        tvStatus = findViewById(R.id.tv_status);
        overlayConnecting = findViewById(R.id.overlay_connecting);
        tvConnectingMessage = findViewById(R.id.tv_connecting_message);
        displayContainer = findViewById(R.id.display_container);
        cursorView = findViewById(R.id.cursor_view);
        surfaceView = findViewById(R.id.surface_view);
        keyboardInput = findViewById(R.id.keyboard_input);
        fabMenu = findViewById(R.id.fab_menu);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        extraKeysPanel = findViewById(R.id.extra_keys_panel);
        nativeExtraKeys = new NativeExtraKeysPanel(extraKeysPanel);
        phyKeyboard = findViewById(R.id.phy_keyboard);
        phyKeyboard.setKeyListener(nativeExtraKeys);
        // The physical keyboard's Shift/Ctrl/Alt/Win mirror the panel's sticky-modifier state.
        extraKeysPanel.setModifierStateObserver(() -> phyKeyboard.refreshModifiers(
            extraKeysPanel.isCtrlDown(), extraKeysPanel.isAltDown(),
            extraKeysPanel.isShiftDown(), extraKeysPanel.isWinDown()));
        extraKeysPanel.setZoneListener(new DisplayExtraKeysPanel.ZoneListener() {
            @Override
            public void onToggleFnxZone() {
                chrome.toggleFnxZone();
            }

            @Override
            public void onShowSystemKeyboard() {
                toggleSoftKeyboard();
            }
        });
        phyKeyboard.setZoneListener(new DisplayPhysicalKeyboardView.ZoneListener() {
            @Override
            public void onToggleExtraZone() {
                chrome.toggleExtraZone();
            }

            @Override
            public void onToggleFnxZone() {
                chrome.toggleFnxZone();
            }

            @Override
            public void onCloseKeyboard() {
                chrome.setKeyboardMode(KeyboardMode.NONE);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupViews() {
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        // The container's layout size IS the display area: chrome visibility, IME and rotation
        // all funnel into it through normal layout. The viewport handles degenerate sizes itself.
        displayContainer.addOnLayoutChangeListener((
            v, l, t, r, b, ol, ot, or2, ob
        ) -> {
            int cw = r - l, ch = b - t;
            v.post(() -> viewport.setArea(cw, ch));
        });
        surfaceView.setOnTouchListener(this::onSurfaceTouch);
        // MOUSE/TABLET gestures live on the container: the whole display area (letterbox included)
        // is gesture surface; the translator pins coordinate ops to the rendered surface rect.
        // In TOUCH mode this listener declines and raw multi-touch stays on the surface view.
        displayContainer.setOnTouchListener(this::onContainerTouch);
        // Host mouse/stylus: scroll wheel + right/middle buttons come as generic-motion events,
        // hover comes as hover events; both feed the pointer device so right-click/scroll/hover
        // pass through to the guest (tablet mode gives absolute hover).
        surfaceView.setOnGenericMotionListener(this::onSurfaceGenericMotion);
        // Also on the container: a right-click over the letterbox area (outside the surface) must
        // still be consumed or the framework synthesizes BACK from it.
        displayContainer.setOnGenericMotionListener(this::onSurfaceGenericMotion);
        surfaceView.setOnHoverListener(this::onSurfaceHover);
        // Restore the persisted mode here rather than on the daemon attach: the FAB menu is
        // reachable before the binder arrives, and a menu built on a stale TOUCH would write that
        // back over the stored mode.
        inputMode = InputMode.fromOrdinal(
            getSharedPreferences(INPUT_PREFS, MODE_PRIVATE).getInt(KEY_INPUT_MODE, 0));
        gestureTranslator = new PointerGestureTranslator(mainHandler, gestureListener);
        gestureTranslator.setAbsolute(inputMode == InputMode.TABLET);
        // Keep the display area out of the system-gesture zones so multi-finger gestures
        // (two-finger right-click/scroll, three-finger zoom) don't trip OEM gestures like
        // three-finger screenshot or edge-back.
        displayContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) ->
            v.setSystemGestureExclusionRects(
                java.util.Collections.singletonList(new android.graphics.Rect(0, 0, r - l, b - t))));
        keyboardInput.setTextInputListener(new NativeKeyboardEditText.TextInputListener() {
            @Override
            public void onCommitText(@NonNull CharSequence text) {
                forwardText(text);
            }

            @Override
            public void onDeleteSurrounding(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) tapKey(KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < afterLength; i++) tapKey(KeyEvent.KEYCODE_FORWARD_DEL);
            }
        });
        var listener = new DragTouchListener(this, this::showFabMenu);
        fabMenu.setOnTouchListener(listener);
    }

    // Soft keyboards that commit text (instead of sending key events) land here; translate each
    // character to its evdev key sequence and forward it. Each char is resolved on its own so one
    // unrepresentable character can't drop the whole commit. Uppercase letters and shifted symbols
    // go through the deterministic US-layout table (Shift synthesized around the key); anything it
    // doesn't cover falls back to the framework key character map.
    private void forwardText(@NonNull CharSequence text) {
        if (inputForwarder == null || !connected) return;
        // Wrap with the extra-keys panel modifiers so e.g. Ctrl+(typed key) reaches the guest.
        nativeExtraKeys.applyModifiers(true);
        String s = text.toString();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inputForwarder.sendChar(c)) continue;
            KeyEvent[] events = keyCharacterMap.getEvents(new char[]{c});
            if (events == null) continue;
            for (KeyEvent e : events) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    inputForwarder.sendKeyEvent(e.getKeyCode(), true);
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    inputForwarder.sendKeyEvent(e.getKeyCode(), false);
                }
            }
        }
        nativeExtraKeys.applyModifiers(false);
    }

    private void tapKey(int keyCode) {
        if (inputForwarder == null || !connected) return;
        nativeExtraKeys.applyModifiers(true);
        inputForwarder.sendKeyEvent(keyCode, true);
        inputForwarder.sendKeyEvent(keyCode, false);
        nativeExtraKeys.applyModifiers(false);
    }

    // TOUCH mode only: raw multi-touch stays on the surface view, which is sized to the guest
    // aspect ratio, so offsets are zero and view coords normalize straight to the multi-touch
    // device's fixed ABS range. (Touch coords stay in view-local space even when the three-finger
    // zoom transform is applied - Android inverse-maps them.) MOUSE/TABLET decline here so the
    // event bubbles up to the container.
    private boolean onSurfaceTouch(View v, MotionEvent event) {
        if (inputMode != InputMode.TOUCH) return false;
        if (inputForwarder == null || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        if (isMouseButtonTouch(event)) return true;
        var tf = TouchScaleCalculator.compute(v.getWidth(), v.getHeight());
        inputForwarder.sendTouchEvent(event, tf.scaleX, tf.scaleY);
        return true;
    }

    // MOUSE/TABLET gesture surface: the whole container is active; the translator pins gestures
    // that carry guest coordinates to the rendered surface rect.
    private boolean onContainerTouch(View v, MotionEvent event) {
        if (inputMode == InputMode.TOUCH) return false;
        if (inputForwarder == null || gestureTranslator == null) return false;
        if (isMouseButtonTouch(event)) return true;
        // TABLET absolute coords go to the normalized-range evdev absolute-mouse device; MOUSE
        // REL deltas are guest px, so they scale against the guest resolution instead.
        float unitW = inputMode == InputMode.TABLET
            ? EvdevEncoder.NORMALIZED_ABS_MAX : guestWidth;
        float unitH = inputMode == InputMode.TABLET
            ? EvdevEncoder.NORMALIZED_ABS_MAX : guestHeight;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointerDriveActive = true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pointerDriveActive = false;
                break;
            default:
                break;
        }
        return gestureTranslator.onTouchEvent(event, displayRectInContainer(), unitW, unitH);
    }

    /**
     * The guest moved its hardware cursor. Places the overlay and, only while the user is actually
     * driving the pointer with a finger in relative mode, pans a zoomed view to keep it visible.
     *
     * The gate is deliberately narrow. In relative mode a short drag can send the guest pointer a
     * long way, so it can leave a zoomed viewport without the finger ever nearing the screen edge
     * -- that is the case worth following. A remote desktop session driving the same guest cursor
     * must NOT drag this device's view around under someone else's hand.
     */
    private void onGuestCursorMoved(int gx, int gy) {
        // crosvm sends u32::MAX,MAX when the guest hides its pointer (UPDATE_CURSOR resource_id=0,
        // which is what switching to a text console does). Without acting on it the overlay keeps
        // showing the last cursor image on a console that should have none. A real position is a
        // framebuffer coordinate, so this value can never be a genuine one.
        if (gx == -1 && gy == -1) {   // u32::MAX arrives as -1 in Java's signed int
            lastCursorX = -1;
            lastCursorY = -1;
            if (cursorView != null) {
                cursorView.setVisibility(GONE);
            }
            return;
        }
        lastCursorX = gx;
        lastCursorY = gy;
        positionCursorOverlay();
        if (pointerDriveActive && inputMode == InputMode.MOUSE && viewport != null) {
            viewport.panToShowContentPoint(gx, gy, CURSOR_FOLLOW_MARGIN_PX);
        }
    }

    /**
     * Put the 64x64 cursor overlay where the guest says its pointer is.
     *
     * Same transform the scanout gets: content is centred in the container, scaled about its
     * centre, then displaced by the pan offset. The overlay is scaled too -- the guest's cursor is
     * in guest pixels, so at 2x zoom it has to double like everything else, or the pointer shrinks
     * relative to what it is pointing at. Pivot goes to (0,0) so scaling grows the image away from
     * the hotspot corner instead of around its middle.
     */
    private void positionCursorOverlay() {
        if (cursorView == null || vpBaseW <= 0 || guestWidth <= 0 || guestHeight <= 0) {
            return;
        }
        if (lastCursorX < 0) {
            return; // no position yet
        }
        View area = (View) surfaceView.getParent();
        if (area == null || area.getWidth() <= 0) {
            return;
        }
        float vx = lastCursorX * vpBaseW / guestWidth;
        float vy = lastCursorY * vpBaseH / guestHeight;
        float cx = area.getWidth() / 2f + vpOffsetX + (vx - vpBaseW / 2f) * vpViewScale;
        float cy = area.getHeight() / 2f + vpOffsetY + (vy - vpBaseH / 2f) * vpViewScale;
        float pxPerGuestPx = (vpBaseW / (float) guestWidth) * vpViewScale;

        cursorView.setPivotX(0f);
        cursorView.setPivotY(0f);
        cursorView.setScaleX(pxPerGuestPx);
        cursorView.setScaleY(pxPerGuestPx);
        cursorView.setTranslationX(cx);
        cursorView.setTranslationY(cy);
        if (cursorView.getVisibility() != VISIBLE) {
            cursorView.setVisibility(VISIBLE);
        }
    }

    // A hardware-mouse right/middle press also arrives on the touch stream (ACTION_DOWN with the
    // button in buttonState). Those are delivered by the generic-motion handler; keep them out of
    // the tap/gesture path (else right-click doubles as a left tap) but consume them so the
    // framework doesn't synthesize a BACK key from an unhandled right-click.
    private boolean isMouseButtonTouch(@NonNull MotionEvent event) {
        if ((event.getSource() & android.view.InputDevice.SOURCE_MOUSE) != 0
            && (event.getButtonState() & (MotionEvent.BUTTON_SECONDARY
                | MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
            lastMouseButtonMs = android.os.SystemClock.uptimeMillis();
            return true;
        }
        return false;
    }

    // Where the guest frame is rendered, in container coordinates: the letterbox-fitted surface
    // bounds mapped through the viewport's current zoom/pan transform.
    @NonNull
    private RectF displayRectInContainer() {
        var rect = new RectF(0, 0, surfaceView.getWidth(), surfaceView.getHeight());
        surfaceView.getMatrix().mapRect(rect);
        rect.offset(surfaceView.getLeft(), surfaceView.getTop());
        return rect;
    }

    // Host mouse/stylus scroll wheel and right/middle buttons (left stays on the touch/tap path).
    // Button presses are ALWAYS consumed - an unhandled BUTTON_SECONDARY press is what makes the
    // framework synthesize a BACK key, which must never fire inside the VM display.
    private boolean onSurfaceGenericMotion(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_SCROLL:
                if (inputForwarder != null) {
                    inputForwarder.sendScroll(
                        Math.round(event.getAxisValue(MotionEvent.AXIS_VSCROLL)),
                        Math.round(event.getAxisValue(MotionEvent.AXIS_HSCROLL)));
                }
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE: {
                lastMouseButtonMs = android.os.SystemClock.uptimeMillis();
                short btn = mapActionButton(event.getActionButton());
                if (btn != 0 && inputForwarder != null) {
                    inputForwarder.sendPointerButton(btn,
                        event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS);
                }
                return true;
            }
            default:
                return false;
        }
    }

    // Host pointer hover (no button): TABLET mode only - absolute hover on the guest tablet.
    // MOUSE/TOUCH modes deliberately ignore Android-side hover.
    private boolean onSurfaceHover(View v, MotionEvent event) {
        if (inputForwarder == null || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        if (inputMode != InputMode.TABLET) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER) {
            var tf = TouchScaleCalculator.compute(v.getWidth(), v.getHeight());
            inputForwarder.sendHover(event.getX(), event.getY(), tf.scaleX, tf.scaleY);
            return true;
        }
        return false;
    }

    private static short mapActionButton(int actionButton) {
        switch (actionButton) {
            case MotionEvent.BUTTON_SECONDARY:
            case MotionEvent.BUTTON_STYLUS_PRIMARY:
                return EvdevEncoder.BTN_RIGHT;
            case MotionEvent.BUTTON_TERTIARY:
                return EvdevEncoder.BTN_MIDDLE;
            default:
                return 0;
        }
    }

    // Sink for InputForwarder: ships encoded evdev to the daemon, which owns the crosvm input
    // sockets and writes them to the guest. Called on the (single) InputForwarder worker thread, so
    // the synchronous request keeps events ordered and back-pressured.
    private boolean sendInputToDaemon(int channel, @NonNull byte[] data) {
        try {
            var req = new JSONObject();
            req.put("command", "vm_input");
            req.put("vm_id", vmId);
            req.put("channel", channel);
            req.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
            var resp = DaemonConnection.getInstance().request(req);
            return resp.optBoolean("delivered", false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();
        // A hardware-mouse right-click the framework (or OEM ROM) failed to see consumed gets
        // synthesized as a BACK key - sometimes mouse-sourced, sometimes (OEM injection) claiming
        // a keyboard/virtual source. Swallow BACK when it's mouse-sourced OR arrives right after
        // any mouse right/middle-button activity; the click itself already went to the guest.
        if (keyCode == KeyEvent.KEYCODE_BACK
            && ((event.getSource() & android.view.InputDevice.SOURCE_MOUSE) != 0
                || android.os.SystemClock.uptimeMillis() - lastMouseButtonMs
                    < MOUSE_BACK_SUPPRESS_MS))
            return true;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            return super.dispatchKeyEvent(event);
        if (inputForwarder != null && connected) {
            // Don't wrap a hardware modifier key in the panel's sticky modifiers; only real keys.
            boolean modifier = isModifierKey(keyCode);
            boolean handled;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (!modifier && nativeExtraKeys.hasNonStickyModifiers())
                    nativeExtraKeys.applyModifiers(true);
                handled = inputForwarder.sendKeyEvent(keyCode, true);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handled = inputForwarder.sendKeyEvent(keyCode, false);
                if (!modifier && nativeExtraKeys.hasNonStickyModifiers())
                    nativeExtraKeys.applyModifiers(false);
            } else {
                handled = false;
            }
            if (handled) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return true;
            default:
                return false;
        }
    }

    // Wires the viewport controller (single writer of the SurfaceView geometry), the chrome
    // controller (single writer of toolbar/status bar/extra keys/system bars visibility) and the
    // window-insets listener that turns system bars + IME into root padding, which in turn sizes
    // the display container.
    private void setupLayoutControllers() {
        int minAreaPx = Math.round(MIN_AREA_DP * getResources().getDisplayMetrics().density);
        viewport = new DisplayViewportController(minAreaPx,
            new DisplayViewportController.Listener() {
                @Override
                public void onViewportChanged(int baseW, int baseH, float viewScale,
                                              float offsetX, float offsetY) {
                    surfaceView.setLayoutParams(new FrameLayout.LayoutParams(baseW, baseH, CENTER));
                    surfaceView.setScaleX(viewScale);
                    surfaceView.setScaleY(viewScale);
                    surfaceView.setTranslationX(offsetX);
                    surfaceView.setTranslationY(offsetY);
                    vpBaseW = baseW;
                    vpBaseH = baseH;
                    vpViewScale = viewScale;
                    vpOffsetX = offsetX;
                    vpOffsetY = offsetY;
                    positionCursorOverlay();
                }

                @Override
                public void onGuestResizeWanted(int areaW, int areaH) {
                    // Auto-resize Guest Display: no guest-side channel on this path yet.
                }
            });
        viewport.setContentSize(guestWidth, guestHeight);

        var inputPrefs = getSharedPreferences(INPUT_PREFS, MODE_PRIVATE);
        chrome = new DisplayChromeController(
            KeyboardMode.fromName(inputPrefs.getString(KEY_KEYBOARD_MODE, null)),
            inputPrefs.getBoolean(KEY_ZONE_EXTRA, true),
            inputPrefs.getBoolean(KEY_ZONE_FNX, false),
            (fullscreen, mode, extraVisible, fnxVisible) -> {
                toolbar.setVisibility(fullscreen ? GONE : VISIBLE);
                statusBar.setVisibility(fullscreen ? GONE : VISIBLE);
                extraKeysPanel.applyZones(
                    extraVisible, fnxVisible, mode == KeyboardMode.SYSTEM);
                phyKeyboard.setZoneToggleState(extraVisible, fnxVisible);
                phyKeyboard.setVisibleAnimated(mode == KeyboardMode.LAPTOP);
                var controller = getWindow().getInsetsController();
                if (controller != null) {
                    if (fullscreen) {
                        controller.hide(WindowInsets.Type.systemBars());
                        controller.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        controller.show(WindowInsets.Type.systemBars());
                    }
                }
                // Re-request insets so the root padding (and thus the display area) updates in the
                // same pass as the visibility changes.
                ViewCompat.requestApplyInsets(findViewById(R.id.main));
            });
        chrome.setStateListener((mode, extraVisible, fnxVisible) -> inputPrefs.edit()
            .putString(KEY_KEYBOARD_MODE, mode.name())
            .putBoolean(KEY_ZONE_EXTRA, extraVisible)
            .putBoolean(KEY_ZONE_FNX, fnxVisible)
            .apply());
        chrome.applyInitial();

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // The Main row's shared slot follows the IME: Fn while it is up, "show IME" while
            // it is down. The system owns that visibility, so read it rather than track it.
            extraKeysPanel.setImeVisible(insets.isVisible(WindowInsetsCompat.Type.ime()));
            boolean fullscreen = chrome != null && chrome.isFullscreen();
            int top = fullscreen ? 0 : sysBars.top;
            int bottom = Math.max(fullscreen ? 0 : sysBars.bottom, ime.bottom);
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void setStatus(String text, int colorRes) {
        tvStatus.setText(text);
        var indicator = new GradientDrawable();
        indicator.setShape(GradientDrawable.OVAL);
        indicator.setColor(getColor(colorRes));
        statusIndicator.setBackground(indicator);
    }

    private void showOverlay(String message) {
        overlayConnecting.setVisibility(VISIBLE);
        tvConnectingMessage.setText(message);
    }

    private void hideOverlay() {
        overlayConnecting.setVisibility(GONE);
    }

    private void toggleSoftKeyboard() {
        var imm = getSystemService(InputMethodManager.class);
        if (imm == null) return;
        // Post so the fab-menu popup has finished tearing down: it still owns the touch-driven
        // focus transition synchronously after the item click, so requesting focus + showing the
        // IME inline lands before our editor is the served view and does nothing.
        mainHandler.post(() -> tryShowKeyboard(imm, 15));
    }

    // Drive a real (invisible) EditText: a SurfaceView is an unreliable IME target on some ROMs.
    // showSoftInput() can return true for a view the IMM isn't serving yet and show nothing, so the
    // success test is imm.isActive(editor), retried on a short delay until the input connection is
    // live. The last few rounds force the IME (some ROMs ignore the implicit request).
    private void tryShowKeyboard(@NonNull InputMethodManager imm, int attemptsLeft) {
        if (attemptsLeft <= 0 || isFinishing()) return;
        keyboardInput.requestFocusFromTouch();
        keyboardInput.requestFocus();
        int flag = attemptsLeft <= 3
            ? InputMethodManager.SHOW_FORCED : InputMethodManager.SHOW_IMPLICIT;
        imm.showSoftInput(keyboardInput, flag);
        if (keyboardInput.isFocused() && imm.isActive(keyboardInput)) return;
        mainHandler.postDelayed(() -> tryShowKeyboard(imm, attemptsLeft - 1), 60);
    }

    private void toggleFullscreen() {
        chrome.toggleFullscreen();
    }

    private void showFabMenu() {
        var popup = new MaterialMenu(this, fabMenu);
        popup.inflate(R.menu.menu_native_display_menu);
        var header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.addView(buildInputModeHeader(popup));
        header.addView(DisplayKeyboardMenuRow.build(
            getLayoutInflater(), chrome.getKeyboardMode(), this::applyKeyboardMode,
            popup::dismiss));
        popup.setHeaderView(header);
        popup.setOnMenuItemClickListener(this::onMenuItemClicked);
        popup.show();
    }

    // Menu header: one row of three icon buttons (touch / tablet / mouse), active mode checked.
    private View buildInputModeHeader(MaterialMenu popup) {
        var group = (com.google.android.material.button.MaterialButtonToggleGroup)
            getLayoutInflater().inflate(R.layout.view_input_mode_toggle, null);
        group.check(inputMode == InputMode.MOUSE ? R.id.mode_mouse
            : inputMode == InputMode.TABLET ? R.id.mode_tablet : R.id.mode_touch);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            setInputModeTo(checkedId == R.id.mode_mouse ? InputMode.MOUSE
                : checkedId == R.id.mode_tablet ? InputMode.TABLET : InputMode.TOUCH);
            popup.dismiss();
        });
        return group;
    }

    // Selecting the system keyboard summons the IME; anything else puts it away, so the mode
    // and what is actually on screen agree.
    private void applyKeyboardMode(@NonNull KeyboardMode mode) {
        chrome.setKeyboardMode(mode);
        if (mode == KeyboardMode.SYSTEM) toggleSoftKeyboard();
        else hideSoftKeyboard();
    }

    // Dropping the editor's focus first matters: it is what the IME is attached to, and some
    // ROMs re-show the keyboard for a still-focused editor right after a hide request.
    private void hideSoftKeyboard() {
        keyboardInput.clearFocus();
        var controller = WindowCompat.getInsetsController(getWindow(), keyboardInput);
        controller.hide(WindowInsetsCompat.Type.ime());
        var imm = getSystemService(InputMethodManager.class);
        if (imm != null)
            imm.hideSoftInputFromWindow(findViewById(R.id.main).getWindowToken(), 0);
    }

    private boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_fullscreen) {
            toggleFullscreen();
            return true;
        } else if (id == R.id.menu_rotate) {
            toggleOrientation();
            return true;
        }
        return false;
    }

    // Select TOUCH/MOUSE/TABLET: persist (shared with VNC) and route the InputForwarder + gesture
    // translator to the matching virtio-input device. The segmented header shows the active mode.
    private void setInputModeTo(@NonNull InputMode mode) {
        if (inputMode == mode) return;
        inputMode = mode;
        getSharedPreferences(INPUT_PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_INPUT_MODE, inputMode.ordinal()).apply();
        if (inputForwarder != null) inputForwarder.setInputMode(inputMode);
        if (gestureTranslator != null) {
            gestureTranslator.setAbsolute(inputMode == InputMode.TABLET);
            gestureTranslator.reset();
        }
    }

    private void toggleOrientation() {
        boolean landscape = getResources().getConfiguration().orientation
            == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape
            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A VM display is on screen and rendering: tell the platform this is sustained heavy
        // gameplay so its power policy raises clocks (see GamePerfHint).
        GamePerfHint.enterGameplay(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        GamePerfHint.exitGameplay(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (displaySource != null) {
            displaySource.shutdown();
            displaySource = null;
        }
        if (inputForwarder != null) {
            inputForwarder.close();
            inputForwarder = null;
        }
        if (directSink != null) {
            directSink.close();
            directSink = null;
        }
        if (displayAttach != null) {
            displayAttach.stop();
            displayAttach = null;
        }
    }

    @NonNull
    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
