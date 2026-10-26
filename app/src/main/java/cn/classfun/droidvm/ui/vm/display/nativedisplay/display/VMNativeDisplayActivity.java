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
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.SensorManager;
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
import android.widget.Toast;

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

import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.DroidVMApp;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.ForegroundCallback;
import cn.classfun.droidvm.lib.store.vm.MotionSensors;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.DragTouchListener;
import cn.classfun.droidvm.lib.ui.ImeInsetsExempt;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.vm.display.base.DaemonDisplayAttach;
import cn.classfun.droidvm.ui.vm.display.base.PhysicalKeyboardGrab;
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
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.HostMotionSensor;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.KeyCodeMapper;
import cn.classfun.droidvm.lib.perf.GamePerfHint;
import cn.classfun.droidvm.lib.perf.SystemGestureGuard;
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
public final class VMNativeDisplayActivity extends AppCompatActivity
    implements ImeInsetsExempt, ForegroundCallback {
    private static final String TAG = "VMNativeDisplay";
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_VM_ID = "vm_id";
    /**
     * Which screen this console shows. The display service name is built from it, so it has to
     * come from whoever opened the console -- a VM can have two screens and only one of them is
     * registered under the name this activity waits on.
     */
    public static final String EXTRA_SCREEN = "screen";
    /**
     * Whether that screen was configured with its own absolute input devices. Used only to say
     * why touch is doing nothing; where the events go is the daemon's answer, not this one.
     */
    public static final String EXTRA_INPUT_ENABLED = "input_enabled";
    /**
     * Whether this console corrects the VM's motion sensors (MotionSensors.corrected); without it
     * the console reads the VM store.
     */
    public static final String EXTRA_MOTION_CORRECT = "motion_correct";
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
    // Quarter turns clockwise the guest's picture is shown at (the Display orientation). The guest
    // knows nothing of it: crosvm's buffers keep the guest's size and SurfaceFlinger turns them, and
    // touches are turned back here before they go to the guest.
    private int displayQuarterTurns = 0;
    // The Screen orientation: forced landscape or portrait, or following the system. With corrected
    // motion sensors it also says who rotates: Android, while it turns the window with the device;
    // the guest otherwise (ScreenMode.windowFollowsDevice, HostMotionSensor). How the system
    // answered a forced one is screenOutcome. Both orientations are kept per screen of a VM.
    private ScreenMode screenMode = ScreenMode.FOLLOW_SYSTEM;
    private ScreenMode.Outcome screenOutcome = ScreenMode.Outcome.FOLLOWING;
    // The system's auto-rotate setting (its rotation lock off), watched while in front.
    private final ContentObserver autoRotateObserver = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateMotionPicture();
        }
    };
    // Whether the VM's motion sensors are corrected here, and what corrects them while this console
    // is in front (null until the root service is attached, and without correction).
    private boolean motionCorrect = false;
    private HostMotionSensor motionSensor;
    private boolean resumed = false;

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
    /**
     * The screen this console shows. It picks the display service to wait on, and it picks which
     * screen's absolute input devices the touches land on -- the two devices are per screen, so
     * "the VM's touchscreen" is not a thing that can be addressed any more.
     */
    private String screenId = VMScreenConfig.ID_GPU0;
    /** Whether that screen has absolute input devices at all; see {@link #EXTRA_INPUT_ENABLED}. */
    private boolean screenInputEnabled = true;
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
    private static final String KEY_DISPLAY_TURNS = "display_quarter_turns_";
    private static final String KEY_SCREEN_MODE = "screen_mode_";
    // Device-wide: this display was seen keeping its shape for a forced orientation.
    private static final String KEY_DISPLAY_IGNORES_REQUESTS = "display_ignores_orientation_requests";
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
    /**
     * Where the cursor overlay goes while the guest's pointer is hidden.
     *
     * NOT setVisibility(GONE). A SurfaceView's Surface follows its visibility, so GONE destroys it
     * and the app hands crosvm a removeSurface(cursor). The guest hides and re-shows the pointer
     * all the time -- KDE drops to a software cursor whenever the hardware plane cannot keep up
     * with a fast move, then hands the plane straight back -- and the image comes back in ONE
     * UPDATE_CURSOR, which writes the position down the pipe and flushes the pixels in the same
     * breath. Re-creating a Surface takes a frame plus a binder round trip, so those pixels land
     * in the backend's sink buffer and are dropped; every later MOVE_CURSOR carries a position and
     * no image, leaving the pointer blank until the guest happens to change its shape. Parking the
     * view keeps the Surface alive, so the flush has somewhere to land. Far enough off-screen to
     * be outside any panel, small enough to stay well clear of float/int overflow in the layer.
     */
    private static final float CURSOR_PARKED_PX = -10000f;

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
    // Asks the daemon to take the host's physical keyboard for this console while it is in
    // front and no IME wants it; see PhysicalKeyboardGrab for the whole of the rule.
    private PhysicalKeyboardGrab keyboardGrab;
    // Bits of the lamp masks the daemon sends, which are the Linux LED_* codes: NUML 0, CAPSL 1,
    // SCROLLL 2.
    private static final int LED_MASK_NUM = 1;
    private static final int LED_MASK_CAPS = 1 << 1;
    private static final int LED_MASK_SCROLL = 1 << 2;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_vm_native_display);

        var intent = getIntent();
        vmName = orEmpty(intent.getStringExtra(EXTRA_VM_NAME));
        vmId = orEmpty(intent.getStringExtra(EXTRA_VM_ID));
        screenId = orEmpty(intent.getStringExtra(EXTRA_SCREEN));
        if (screenId.isEmpty()) screenId = VMScreenConfig.ID_GPU0;
        screenInputEnabled = intent.getBooleanExtra(EXTRA_INPUT_ENABLED, true);
        guestWidth = (int) intent.getLongExtra(EXTRA_WIDTH, 1280);
        guestHeight = (int) intent.getLongExtra(EXTRA_HEIGHT, 720);
        vmKey = NativeDisplay.serviceNameFromId(vmId, screenId);
        restoreRotationState();
        resolveMotionCorrect(intent);
        // Before the views: applyInitial() runs while they are being built and tells this
        // the keyboard mode it starts in.
        keyboardGrab = new PhysicalKeyboardGrab(vmId, () -> screenId, screenInputEnabled);

        bindViews();
        toolbar.setTitle(vmName.isEmpty() ? getString(R.string.native_display_title) : vmName);
        toolbar.setNavigationOnClickListener(v -> finish());
        setupViews();
        wireHardwareKeyEcho();
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
                    // The grab has no such fallback: the daemon is the only process that can hold
                    // it, and it has already dropped it with the token.
                    keyboardGrab.setService(null);
                }
            });
        displayAttach.start();
    }

    private void onRootConnected(@NonNull INativeDisplayRootService service) {
        keyboardGrab.setService(service);
        // Try a direct unix-socket sink to the daemon (one write per evdev frame, no IPC
        // round-trip); on any failure it falls back to the vm_input JSON-RPC path below.
        directSink = new DirectInputSink(vmId, () -> screenId, service, this::sendInputToDaemon);
        inputForwarder = new InputForwarder(directSink);
        if (nativeExtraKeys != null) nativeExtraKeys.setForwarder(inputForwarder);
        // A forwarder is built fresh on every attach and starts in TOUCH, so it has to be told
        // the mode this session is actually in (restored in setupViews, or since changed).
        inputForwarder.setInputMode(inputMode);
        setupMotionSensor();

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
                    viewport.setContentSize(shownWidth(), shownHeight());
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
        // Hardware cursor: give crosvm a Surface for the guest's cursor plane and follow the
        // positions it reports. Both are no-ops on a guest that never uses the cursor plane.
        // This must run here, on the main thread with displaySource assigned -- it used to sit
        // inside the binder-supplier lambda above, where the first invocation raced the field
        // assignment on a background thread and the cursor layer was wired only by luck.
        displaySource.setCursorView(cursorView);
        displaySource.setCursorListener(this::onGuestCursorMoved);
        displaySource.setOnQuarterTurnsRejected(turns -> {
            Toast.makeText(this, R.string.display_rotation_unsupported, Toast.LENGTH_LONG).show();
            displayQuarterTurns = 0;
            applyDisplayTurns();
            saveRotationState();
        });
        if (displayQuarterTurns != 0) displaySource.setQuarterTurns(displayQuarterTurns);
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
        // Visible from the start, parked off-screen: the Surface exists before the guest's first
        // UPDATE_CURSOR, which is the only message that carries pointer pixels. Laid out GONE, the
        // first pointer image was flushed into a Surface that did not exist yet and the pointer
        // stayed blank until the next shape change -- the same hole the hide path used to open.
        cursorView.setVisibility(VISIBLE);
        parkCursorOverlay();
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
        if (displayQuarterTurns == 0) {
            var tf = TouchScaleCalculator.compute(v.getWidth(), v.getHeight());
            inputForwarder.sendTouchEvent(event, tf.scaleX, tf.scaleY);
            return true;
        }
        // The view shows the picture turned; the guest's digitizer is the unturned panel. Turn the
        // touch back about the view's centre into a rect of the panel's shape, then scale as usual.
        int w = v.getWidth(), h = v.getHeight();
        int panelW = pictureSideways() ? h : w, panelH = pictureSideways() ? w : h;
        var unturn = unturnAbout(w / 2f, h / 2f);
        unturn.postTranslate((panelW - w) / 2f, (panelH - h) / 2f);
        var unturned = MotionEvent.obtain(event);
        unturned.transform(unturn);
        var tf = TouchScaleCalculator.compute(panelW, panelH);
        inputForwarder.sendTouchEvent(unturned, tf.scaleX, tf.scaleY);
        unturned.recycle();
        return true;
    }

    // The guest's picture as shown: a quarter (or three-quarter) turn makes it as wide as the guest
    // is tall.
    private boolean pictureSideways() {
        return (displayQuarterTurns & 1) != 0;
    }

    private int shownWidth() {
        return pictureSideways() ? guestHeight : guestWidth;
    }

    private int shownHeight() {
        return pictureSideways() ? guestWidth : guestHeight;
    }

    // From the shown picture back to the guest's: the picture's turn undone about a centre point.
    // The unturned picture has the same centre, so a shown rect about it maps onto the guest's.
    @NonNull
    private Matrix unturnAbout(float centreX, float centreY) {
        var m = new Matrix();
        m.setRotate(-90f * displayQuarterTurns, centreX, centreY);
        return m;
    }

    // Where a guest framebuffer point is in the shown picture, in the picture's own units (its size
    // being shownWidth() x shownHeight()). The inverse of unturnAbout, for the cursor overlay.
    @NonNull
    private float[] guestToShown(float gx, float gy) {
        switch (displayQuarterTurns) {
            case 1: return new float[]{guestHeight - gy, gx};
            case 2: return new float[]{guestWidth - gx, guestHeight - gy};
            case 3: return new float[]{gy, guestWidth - gx};
            default: return new float[]{gx, gy};
        }
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
        var rect = displayRectInContainer();
        if (displayQuarterTurns == 0) {
            return gestureTranslator.onTouchEvent(event, rect, unitW, unitH);
        }
        // Undo the picture's turn about its centre: absolute positions land in the unturned
        // picture's rect, and relative moves turn with them.
        float cx = rect.centerX(), cy = rect.centerY();
        float halfW = (pictureSideways() ? rect.height() : rect.width()) / 2f;
        float halfH = (pictureSideways() ? rect.width() : rect.height()) / 2f;
        var unturned = MotionEvent.obtain(event);
        unturned.transform(unturnAbout(cx, cy));
        boolean handled = gestureTranslator.onTouchEvent(unturned,
            new RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH), unitW, unitH);
        unturned.recycle();
        return handled;
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
            parkCursorOverlay();
            return;
        }
        lastCursorX = gx;
        lastCursorY = gy;
        positionCursorOverlay();
        if (pointerDriveActive && inputMode == InputMode.MOUSE && viewport != null) {
            float[] shown = guestToShown(gx, gy);
            viewport.panToShowContentPoint(shown[0], shown[1], CURSOR_FOLLOW_MARGIN_PX);
        }
    }

    /**
     * Take the pointer off screen without letting go of its Surface. See {@link #CURSOR_PARKED_PX}.
     */
    private void parkCursorOverlay() {
        if (cursorView == null) {
            return;
        }
        cursorView.setTranslationX(CURSOR_PARKED_PX);
        cursorView.setTranslationY(CURSOR_PARKED_PX);
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
        float[] shown = guestToShown(lastCursorX, lastCursorY);
        float vx = shown[0] * vpBaseW / shownWidth();
        float vy = shown[1] * vpBaseH / shownHeight();
        float cx = area.getWidth() / 2f + vpOffsetX + (vx - vpBaseW / 2f) * vpViewScale;
        float cy = area.getHeight() / 2f + vpOffsetY + (vy - vpBaseH / 2f) * vpViewScale;
        float pxPerGuestPx = (vpBaseW / (float) shownWidth()) * vpViewScale;
        // crosvm turns the cursor plane with the scanout, so its hotspot corner (the image's top
        // left) moves round the overlay: top right after a quarter turn, and so on.
        float plane = DisplayProvider.CURSOR_PLANE_PX * pxPerGuestPx;
        boolean hotspotRight = displayQuarterTurns == 1 || displayQuarterTurns == 2;
        boolean hotspotBottom = displayQuarterTurns == 2 || displayQuarterTurns == 3;

        cursorView.setPivotX(0f);
        cursorView.setPivotY(0f);
        cursorView.setScaleX(pxPerGuestPx);
        cursorView.setScaleY(pxPerGuestPx);
        cursorView.setTranslationX(hotspotRight ? cx - plane : cx);
        cursorView.setTranslationY(hotspotBottom ? cy - plane : cy);
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
            int w = v.getWidth(), h = v.getHeight();
            int panelW = pictureSideways() ? h : w, panelH = pictureSideways() ? w : h;
            float[] at = {event.getX(), event.getY()};
            var unturn = unturnAbout(w / 2f, h / 2f);
            unturn.postTranslate((panelW - w) / 2f, (panelH - h) / 2f);
            unturn.mapPoints(at);
            var tf = TouchScaleCalculator.compute(panelW, panelH);
            inputForwarder.sendHover(at[0], at[1], tf.scaleX, tf.scaleY);
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
            req.put("screen", screenId);
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
        viewport.setContentSize(shownWidth(), shownHeight());

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
                // SYSTEM is the mode whose text comes from the IME, and an IME cannot see a
                // grabbed keyboard: that mode hands the physical one back to Android.
                keyboardGrab.setKeyboardMode(mode);
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
        View keyboardRow = DisplayKeyboardMenuRow.build(
            getLayoutInflater(), chrome.getKeyboardMode(), this::applyKeyboardMode,
            popup::dismiss);
        // The rotation row goes below: hand it the header's bottom margin, so the three rows sit
        // as evenly apart as the first two.
        keyboardRow.setPaddingRelative(keyboardRow.getPaddingStart(), keyboardRow.getPaddingTop(),
            keyboardRow.getPaddingEnd(), Math.round(2 * getResources().getDisplayMetrics().density));
        header.addView(keyboardRow);
        header.addView(buildRotationRow());
        header.addView(buildScreenModeRow());
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
        }
        return false;
    }

    // Select TOUCH/MOUSE/TABLET: persist (shared with VNC) and route the InputForwarder + gesture
    // translator to the matching virtio-input device. The segmented header shows the active mode.
    private void setInputModeTo(@NonNull InputMode mode) {
        if (inputMode == mode) return;
        // Both absolute modes ride this screen's own devices, so with them switched off the mode
        // is selectable and inert. Say so once, here, rather than leaving the user tapping a
        // screen that answers nothing -- and say the true reason, which is a VM that has to be
        // started again, not a setting that would take effect if they waited.
        //
        // The switch reaches typing too now, since the keyboard became this screen's rather than
        // the VM's. The hint names no device, so it stays true of all three; MOUSE is still the
        // one thing the switch does not touch, the relative pointer being the VM's.
        if (!screenInputEnabled && mode != InputMode.MOUSE)
            Toast.makeText(this, R.string.display_input_disabled_hint, Toast.LENGTH_LONG).show();
        inputMode = mode;
        getSharedPreferences(INPUT_PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_INPUT_MODE, inputMode.ordinal()).apply();
        if (inputForwarder != null) inputForwarder.setInputMode(inputMode);
        if (gestureTranslator != null) {
            gestureTranslator.setAbsolute(inputMode == InputMode.TABLET);
            gestureTranslator.reset();
        }
    }

    // Menu header row, segmented like the two above it: turn the Display left or right, its turn
    // between (a tap sets it back to 0). All three are actions, unchecked again at once.
    private View buildRotationRow() {
        var group = (com.google.android.material.button.MaterialButtonToggleGroup)
            getLayoutInflater().inflate(R.layout.view_rotation_row, null);
        MaterialButton turn = group.findViewById(R.id.display_turn);
        Runnable showTurn = () -> turn.setText(getString(R.string.display_turn_degrees,
            displayQuarterTurns * 90));
        showTurn.run();
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            g.uncheck(checkedId);
            if (checkedId == R.id.display_turn) turnDisplay(-displayQuarterTurns);
            else turnDisplay(checkedId == R.id.rotate_display_left ? -1 : 1);
            showTurn.run();
        });
        return group;
    }

    // The Screen row: force landscape, force portrait, or follow the system; one always checked.
    private View buildScreenModeRow() {
        var group = (com.google.android.material.button.MaterialButtonToggleGroup)
            getLayoutInflater().inflate(R.layout.view_screen_mode_row, null);
        int[] ids = {R.id.screen_follow, R.id.screen_landscape, R.id.screen_portrait};
        group.check(ids[screenMode.ordinal()]);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (var mode : ScreenMode.values())
                if (ids[mode.ordinal()] == checkedId && mode != screenMode) setScreenMode(mode);
        });
        return group;
    }

    /**
     * Turn the guest's picture a quarter turn (Display orientation). The guest's panel keeps its
     * size; corrected motion sensors turn with the picture. The Screen stays as it is.
     */
    private void turnDisplay(int quarterTurns) {
        int turns = (displayQuarterTurns + quarterTurns) & 3;
        if (turns == displayQuarterTurns) return;
        if (displaySource == null || !displaySource.setQuarterTurns(turns)) {
            Toast.makeText(this, R.string.display_rotation_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        displayQuarterTurns = turns;
        applyDisplayTurns();
        saveRotationState();
    }

    /**
     * Force the Screen landscape or portrait, or let it follow the system. The Display orientation
     * stays. A forced one the system does not turn the display for is said so once it has answered.
     */
    private void setScreenMode(@NonNull ScreenMode mode) {
        screenMode = mode;
        applyScreenOrientation();
        saveRotationState();
        // An ignored request changes no configuration, so the answer is looked at after a while.
        mainHandler.postDelayed(() -> checkScreenOutcome(true), 1000);
    }

    private void applyScreenOrientation() {
        setRequestedOrientation(screenMode.request);
        checkScreenOutcome(false);
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        checkScreenOutcome(false);
    }

    /**
     * How the system answered the Screen orientation (ScreenMode.Outcome); [tell] says a forced one
     * it did not turn the display for. A display seen keeping its shape is remembered: a forced
     * orientation that matches it later is only the device held that way, not held still.
     */
    private void checkScreenOutcome(boolean tell) {
        var wm = getWindowManager();
        var display = wm.getMaximumWindowMetrics().getBounds();
        var window = wm.getCurrentWindowMetrics().getBounds();
        screenOutcome = screenMode.outcome(display.width(), display.height(), window.width(),
            window.height());
        if (screenOutcome == ScreenMode.Outcome.LETTERBOXED
            || screenOutcome == ScreenMode.Outcome.IGNORED)
            getSharedPreferences(INPUT_PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_DISPLAY_IGNORES_REQUESTS, true).apply();
        if (tell && screenOutcome == ScreenMode.Outcome.LETTERBOXED)
            Toast.makeText(this, R.string.screen_letterboxed, Toast.LENGTH_LONG).show();
        else if (tell && screenOutcome == ScreenMode.Outcome.IGNORED)
            Toast.makeText(this, R.string.screen_request_ignored, Toast.LENGTH_LONG).show();
        updateMotionPicture();
    }

    // Corrected motion sensors turn the phone's motion by the picture's turn, and keep gravity
    // down the window while Android does the rotating.
    private void updateMotionPicture() {
        if (motionSensor == null) return;
        boolean ignores = getSharedPreferences(INPUT_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_DISPLAY_IGNORES_REQUESTS, false);
        boolean autoRotate = android.provider.Settings.System.getInt(getContentResolver(),
            android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        motionSensor.setPicture(displayQuarterTurns,
            screenMode.windowFollowsDevice(screenOutcome, !ignores, autoRotate));
    }

    // The launching intent says whether to correct; a console opened another way (am start) asks
    // the VM store, off the main thread, and sets the sensors up when the answer comes back.
    private void resolveMotionCorrect(@NonNull Intent intent) {
        if (intent.hasExtra(EXTRA_MOTION_CORRECT)) {
            motionCorrect = intent.getBooleanExtra(EXTRA_MOTION_CORRECT, false);
            return;
        }
        var id = vmId;
        new Thread(() -> {
            var store = new VMStore();
            store.load(this);
            VMConfig config;
            try {
                config = store.findById(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                config = null;
            }
            boolean correct = config != null && MotionSensors.corrected(config.item);
            mainHandler.post(() -> {
                motionCorrect = correct;
                setupMotionSensor();
            });
        }, "MotionSensors").start();
    }

    // (Re)built with every forwarder: a re-attach makes a new one, and the old sensor feed dies
    // with the old forwarder.
    private void setupMotionSensor() {
        if (motionSensor != null) {
            motionSensor.stop();
            motionSensor = null;
        }
        if (!motionCorrect || inputForwarder == null) return;
        var sensors = getSystemService(SensorManager.class);
        if (sensors == null) return;
        motionSensor = new HostMotionSensor(sensors, inputForwarder, () -> {
            var display = getDisplay();
            return display == null ? 0 : display.getRotation();
        });
        updateMotionPicture();
        if (resumed) motionSensor.start();
    }

    // The picture's new shape: the viewport fits it, and the cursor overlay follows.
    private void applyDisplayTurns() {
        if (viewport != null) viewport.setContentSize(shownWidth(), shownHeight());
        positionCursorOverlay();
        updateMotionPicture();
    }

    private void restoreRotationState() {
        var prefs = getSharedPreferences(INPUT_PREFS, MODE_PRIVATE);
        displayQuarterTurns = prefs.getInt(KEY_DISPLAY_TURNS + vmKey, 0) & 3;
        screenMode = ScreenMode.fromOrdinal(prefs.getInt(KEY_SCREEN_MODE + vmKey, 0));
        applyScreenOrientation();
    }

    private void saveRotationState() {
        getSharedPreferences(INPUT_PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_TURNS + vmKey, displayQuarterTurns)
            .putInt(KEY_SCREEN_MODE + vmKey, screenMode.ordinal())
            .apply();
    }

    /**
     * Registry key for the VM-event callback, unique per instance.
     *
     * Not the tag: this activity is recreated (the orientation flip on entry does it), and the
     * incoming instance's onStart() runs before the outgoing one's onStop() -- so a key shared by
     * both has the one leaving unregister the one arriving, and the console then never hears that
     * its VM exited.
     */
    private final String eventKey =
        fmt("%s@%s", TAG, Integer.toHexString(System.identityHashCode(this)));

    @Override
    protected void onStart() {
        super.onStart();
        var handler = ((DroidVMApp) getApplication()).getVMEventHandler();
        if (handler != null) handler.addForegroundCallback(eventKey, this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        var handler = ((DroidVMApp) getApplication()).getVMEventHandler();
        if (handler != null) handler.removeForegroundCallback(eventKey);
    }

    /**
     * The VM this console is attached to has gone. Close with it: what is left otherwise is a
     * console that cannot reconnect, retrying a display service that will never be registered
     * again (which used to cost the daemon dearly -- see NativeDisplayBinder).
     *
     * This is the exit event, not the absence of a crosvm process, and the difference is the
     * point: a VM waiting for the huge-page reserve -- after a start, or between a guest reboot
     * and its relaunch -- has no process either, and must not be mistaken for one that is gone.
     * The daemon fires "rebooting" for that case and holds the exit event back until the VM
     * really is not coming back, so there is nothing to second-guess here.
     *
     * A VM that crashed is the one case worth staying open for. VMEventHandler answers a non-zero
     * exit by putting up the exit dialog -- the tail of the log, and a way into the full one --
     * on whatever activity is in front, and closing that activity out from under it would take
     * the explanation with it. So the console holds; the user closes it after reading. The toast
     * is that handler's job either way, so there is none here.
     */
    @Override
    public void onVMExited(UUID id, String vmName, int exitCode, JSONObject data) {
        if (id == null || !id.toString().equals(vmId)) return;
        if (exitCode != 0) {
            Log.i(TAG, fmt("VM exited with %d -- keeping the console up for the exit dialog",
                exitCode));
            return;
        }
        mainHandler.post(() -> {
            if (isFinishing()) return;
            Log.i(TAG, "VM stopped; closing the display");
            finish();
        });
    }

    /**
     * Draws the grabbed physical keyboard on the on-screen one. The grabbed keys go from the
     * daemon straight into the guest and never through this process, so without this feed the
     * drawn keyboard would sit still while the user types. Registered by the grab only while the
     * laptop keyboard is the mode -- no other mode has a key to light.
     */
    private void wireHardwareKeyEcho() {
        keyboardGrab.setEcho(new PhysicalKeyboardGrab.Echo() {
            @Override
            public void onKeys(@NonNull int[] codes, @NonNull int[] values) {
                for (int i = 0; i < codes.length && i < values.length; i++) {
                    int androidCode = KeyCodeMapper.evdevToAndroid(codes[i]);
                    // A key the drawn keyboard has no face for (media keys, F13 and up) is simply
                    // not drawn; it still reached the guest.
                    if (androidCode != -1)
                        phyKeyboard.setHardwareKeyHeld(androidCode, values[i] != 0);
                }
            }

            @Override
            public void onLeds(int known, int on) {
                phyKeyboard.setLockState(
                    (known & LED_MASK_CAPS) != 0, (on & LED_MASK_CAPS) != 0,
                    (known & LED_MASK_NUM) != 0, (on & LED_MASK_NUM) != 0,
                    (known & LED_MASK_SCROLL) != 0, (on & LED_MASK_SCROLL) != 0);
            }

            @Override
            public void onCleared() {
                phyKeyboard.clearHardwareKeys();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A VM display is on screen and rendering: tell the platform this is sustained heavy
        // gameplay so its power policy raises clocks (see GamePerfHint).
        GamePerfHint.enterGameplay(this);
        // And keep the host's full-screen touch gestures (OEM three-finger screenshot etc.)
        // from eating multi-finger input meant for the guest (see SystemGestureGuard).
        SystemGestureGuard.enterDisplay();
        keyboardGrab.setResumed(true);
        resumed = true;
        getContentResolver().registerContentObserver(android.provider.Settings.System.getUriFor(
            android.provider.Settings.System.ACCELEROMETER_ROTATION), false, autoRotateObserver);
        updateMotionPicture();
        if (motionSensor != null) motionSensor.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        GamePerfHint.exitGameplay(this);
        SystemGestureGuard.exitDisplay();
        keyboardGrab.setResumed(false);
        resumed = false;
        getContentResolver().unregisterContentObserver(autoRotateObserver);
        // crosvm goes back to the phone's own sensors about a second later.
        if (motionSensor != null) motionSensor.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (keyboardGrab != null) keyboardGrab.close();
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
