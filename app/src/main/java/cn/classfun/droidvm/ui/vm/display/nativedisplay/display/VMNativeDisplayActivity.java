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
import android.view.InputDevice;
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
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;
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
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.KeyCodeMapper;
import cn.classfun.droidvm.lib.perf.GamePerfHint;
import cn.classfun.droidvm.lib.perf.SystemGestureGuard;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.NativeExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.NativeKeyboardEditText;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.TouchScaleCalculator;

/**
 * Native display: shows a VM's gfxstream/virtio-gpu output on an Android Surface
 * and forwards touch/keyboard/mouse input as evdev through the daemon.
 */
public final class VMNativeDisplayActivity extends AppCompatActivity
    implements ImeInsetsExempt, ForegroundCallback {

    private static final String TAG = "VMNativeDisplay";

    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_VM_ID = "vm_id";
    public static final String EXTRA_SCREEN = "screen";
    public static final String EXTRA_INPUT_ENABLED = "input_enabled";
    public static final String EXTRA_WIDTH = "display_width";
    public static final String EXTRA_HEIGHT = "display_height";

    private static final String INPUT_PREFS = "droidvm_prefs";
    private static final String KEY_INPUT_MODE = "display_input_mode";
    private static final String KEY_KEYBOARD_MODE = "display_keyboard_mode";
    private static final String KEY_ZONE_EXTRA = "display_keyboard_zone_extra";
    private static final String KEY_ZONE_FNX = "display_keyboard_zone_fnx";

    private static final int MIN_AREA_DP = 96;
    private static final float CURSOR_FOLLOW_MARGIN_PX = 96f;
    private static final float CURSOR_PARKED_PX = -10000f;

    private static final long MOUSE_BACK_SUPPRESS_MS = 800;
    private static final int HOST_MOUSE_BUTTONS =
        MotionEvent.BUTTON_PRIMARY
            | MotionEvent.BUTTON_SECONDARY
            | MotionEvent.BUTTON_TERTIARY;

    private static final int LED_MASK_NUM = 1;
    private static final int LED_MASK_CAPS = 1 << 1;
    private static final int LED_MASK_SCROLL = 1 << 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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
    private NativeKeyboardEditText keyboardInput;
    private FloatingActionButton fabMenu;
    private MaterialButton btnFullscreen;
    private DisplayExtraKeysPanel extraKeysPanel;
    private DisplayPhysicalKeyboardView phyKeyboard;

    private float vpBaseW;
    private float vpBaseH;
    private float vpViewScale = 1f;
    private float vpOffsetX;
    private float vpOffsetY;

    private boolean pointerDriveActive;
    private int lastCursorX = -1;
    private int lastCursorY = -1;

    private String vmName = "";
    private String vmId = "";
    private String vmKey = "";
    private String screenId = VMScreenConfig.ID_GPU0;
    private boolean screenInputEnabled = true;
    private int guestWidth = 1280;
    private int guestHeight = 720;

    private DisplaySource displaySource;
    private InputForwarder inputForwarder;
    private DirectInputSink directSink;
    private NativeExtraKeysPanel nativeExtraKeys;
    private boolean connected;

    private InputMode inputMode = InputMode.TOUCH;
    private PointerGestureTranslator gestureTranslator;
    private float mouseRemX;
    private float mouseRemY;
    private long lastMouseButtonMs;
    private boolean pointerCaptured;
    private float hoverLastX = Float.NaN;
    private float hoverLastY = Float.NaN;

    /**
     * 已转发给 guest 的物理鼠标按键状态。Android 可能为同一次点击同时发送
     * ACTION_DOWN/UP 和 ACTION_BUTTON_PRESS/RELEASE；只转发状态变化，避免双击。
     */
    private int hostMouseButtons;

    private DisplayViewportController viewport;
    private DisplayChromeController chrome;
    private DaemonDisplayAttach displayAttach;
    private PhysicalKeyboardGrab keyboardGrab;

    private final PointerGestureTranslator.Listener gestureListener =
        new PointerGestureTranslator.Listener() {
            @Override
            public void onRelativeMove(float dxGuest, float dyGuest) {
                if (inputForwarder == null) return;
                mouseRemX += dxGuest;
                mouseRemY += dyGuest;
                int dx = (int) mouseRemX;
                int dy = (int) mouseRemY;
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
                    keyboardGrab.setService(null);
                }
            });
        displayAttach.start();
    }

    private void onRootConnected(@NonNull INativeDisplayRootService service) {
        keyboardGrab.setService(service);

        directSink = new DirectInputSink(vmId, () -> screenId, service, this::sendInputToDaemon);
        inputForwarder = new InputForwarder(directSink);
        hostMouseButtons = 0;
        if (nativeExtraKeys != null) nativeExtraKeys.setForwarder(inputForwarder);
        inputForwarder.setInputMode(inputMode);

        DaemonConnection.getInstance().buildRequest("vm_start")
            .put("vm_id", vmId)
            .onResponse(r -> {})
            .onUnsuccessful(r -> {})
            .onError(e -> Log.w(TAG, "vm_start request failed", e))
            .invoke();

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
                    viewport.setContentSize(width, height);
                    if (connected)
                        setStatus(fmt(getString(R.string.native_display_connected),
                            guestWidth, guestHeight), R.color.vnc_status_connected);
                }

                @Override
                public void onStateChanged(@NonNull DisplaySource.State state) {
                    onDisplayStateChanged(state);
                }
            });

        displaySource.setCursorView(cursorView);
        displaySource.setCursorListener(this::onGuestCursorMoved);
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

        displayContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            int cw = r - l;
            int ch = b - t;
            v.post(() -> viewport.setArea(cw, ch));
        });

        surfaceView.setOnTouchListener(this::onSurfaceTouch);
        displayContainer.setOnTouchListener(this::onContainerTouch);

        surfaceView.setOnGenericMotionListener(this::onSurfaceGenericMotion);
        displayContainer.setOnGenericMotionListener(this::onSurfaceGenericMotion);
        surfaceView.setOnHoverListener(this::onSurfaceHover);
        displayContainer.setOnHoverListener(this::onContainerHover);

        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.setOnCapturedPointerListener(this::onCapturedPointerEvent);

        inputMode = InputMode.fromOrdinal(
            getSharedPreferences(INPUT_PREFS, MODE_PRIVATE).getInt(KEY_INPUT_MODE, 0));
        gestureTranslator = new PointerGestureTranslator(mainHandler, gestureListener);
        gestureTranslator.setAbsolute(inputMode == InputMode.TABLET);

        displayContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) ->
            v.setSystemGestureExclusionRects(
                java.util.Collections.singletonList(
                    new android.graphics.Rect(0, 0, r - l, b - t))));

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

    private void forwardText(@NonNull CharSequence text) {
        if (inputForwarder == null || !connected) return;
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

    /**
     * 鼠标点击不走手指触摸或手势转换器。三个按键均通过鼠标 evdev 通道转发，
     * 不受 TOUCH/MOUSE/TABLET 模式影响。
     */
    private boolean onSurfaceTouch(View v, MotionEvent event) {
        if (isPhysicalMouse(event)) {
            forwardPhysicalMouseButtons(event);
            return true;
        }

        if (inputMode != InputMode.TOUCH) return false;
        if (inputForwarder == null || v.getWidth() <= 0 || v.getHeight() <= 0) return false;

        var tf = TouchScaleCalculator.compute(v.getWidth(), v.getHeight());
        inputForwarder.sendTouchEvent(event, tf.scaleX, tf.scaleY);
        return true;
    }

    private boolean onContainerTouch(View v, MotionEvent event) {
        if (isPhysicalMouse(event)) {
            forwardPhysicalMouseButtons(event);
            return true;
        }

        if (inputMode == InputMode.TOUCH) return false;
        if (inputForwarder == null || gestureTranslator == null) return false;

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

    private static boolean isPhysicalMouseSource(int source) {
        return (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
    }

    private static boolean isPhysicalMouse(@NonNull MotionEvent event) {
        return isPhysicalMouseSource(event.getSource());
    }

    /**
     * 将 Android 鼠标三键的当前状态与已发送状态比较，只发送发生变化的键。
     *
     * 同一次点击可能先后出现在 touch、generic motion、captured pointer 回调里。
     * BUTTON_PRESS/RELEASE 的 getButtonState() 在部分设备上仍是变化前的状态，
     * 因此优先按 getActionButton() 修正本次事件的状态。
     */
    private void forwardPhysicalMouseButtons(@NonNull MotionEvent event) {
        if (!isPhysicalMouse(event)) return;

        int buttons = event.getButtonState() & HOST_MOUSE_BUTTONS;
        int action = event.getActionMasked();
        int actionButton = event.getActionButton() & HOST_MOUSE_BUTTONS;

        if (action == MotionEvent.ACTION_BUTTON_PRESS) {
            buttons |= actionButton;
        } else if (action == MotionEvent.ACTION_BUTTON_RELEASE) {
            buttons &= ~actionButton;
        } else if (action == MotionEvent.ACTION_DOWN) {
            // 部分设备在 DOWN 中尚未更新 buttonState。
            if (buttons == 0 && actionButton == 0)
                buttons |= MotionEvent.BUTTON_PRIMARY;
        } else if (action == MotionEvent.ACTION_UP
            || action == MotionEvent.ACTION_CANCEL) {
            // UP/CANCEL 释放普通左键；右/中键仍由 BUTTON_RELEASE 或 buttonState 管理。
            buttons &= ~MotionEvent.BUTTON_PRIMARY;
        }

        int changed = hostMouseButtons ^ buttons;
        hostMouseButtons = buttons;

        if ((changed & (MotionEvent.BUTTON_SECONDARY
            | MotionEvent.BUTTON_TERTIARY)) != 0) {
            lastMouseButtonMs = android.os.SystemClock.uptimeMillis();
        }

        if (inputForwarder == null) return;

        if ((changed & MotionEvent.BUTTON_PRIMARY) != 0) {
            inputForwarder.sendPointerButton(EvdevEncoder.BTN_LEFT,
                (buttons & MotionEvent.BUTTON_PRIMARY) != 0);
        }
        if ((changed & MotionEvent.BUTTON_SECONDARY) != 0) {
            inputForwarder.sendPointerButton(EvdevEncoder.BTN_RIGHT,
                (buttons & MotionEvent.BUTTON_SECONDARY) != 0);
        }
        if ((changed & MotionEvent.BUTTON_TERTIARY) != 0) {
            inputForwarder.sendPointerButton(EvdevEncoder.BTN_MIDDLE,
                (buttons & MotionEvent.BUTTON_TERTIARY) != 0);
        }
    }

    /** 失焦或销毁时释放 guest 中可能仍处于按下状态的鼠标键。 */
    private void releasePhysicalMouseButtons() {
        int buttons = hostMouseButtons;
        hostMouseButtons = 0;
        if (inputForwarder == null) return;

        if ((buttons & MotionEvent.BUTTON_PRIMARY) != 0)
            inputForwarder.sendPointerButton(EvdevEncoder.BTN_LEFT, false);
        if ((buttons & MotionEvent.BUTTON_SECONDARY) != 0)
            inputForwarder.sendPointerButton(EvdevEncoder.BTN_RIGHT, false);
        if ((buttons & MotionEvent.BUTTON_TERTIARY) != 0)
            inputForwarder.sendPointerButton(EvdevEncoder.BTN_MIDDLE, false);
    }

    private void onGuestCursorMoved(int gx, int gy) {
        if (gx == -1 && gy == -1) {
            lastCursorX = -1;
            lastCursorY = -1;
            parkCursorOverlay();
            return;
        }

        lastCursorX = gx;
        lastCursorY = gy;
        positionCursorOverlay();
        if (pointerDriveActive && inputMode == InputMode.MOUSE && viewport != null) {
            viewport.panToShowContentPoint(gx, gy, CURSOR_FOLLOW_MARGIN_PX);
        }
    }

    private void parkCursorOverlay() {
        if (cursorView == null) return;
        cursorView.setTranslationX(CURSOR_PARKED_PX);
        cursorView.setTranslationY(CURSOR_PARKED_PX);
    }

    private void positionCursorOverlay() {
        if (cursorView == null || vpBaseW <= 0 || guestWidth <= 0 || guestHeight <= 0)
            return;
        if (lastCursorX < 0) return;

        View area = (View) surfaceView.getParent();
        if (area == null || area.getWidth() <= 0) return;

        float vx = lastCursorX * vpBaseW / guestWidth;
        float vy = lastCursorY * vpBaseH / guestHeight;
        float cx = area.getWidth() / 2f + vpOffsetX
            + (vx - vpBaseW / 2f) * vpViewScale;
        float cy = area.getHeight() / 2f + vpOffsetY
            + (vy - vpBaseH / 2f) * vpViewScale;
        float pxPerGuestPx = (vpBaseW / (float) guestWidth) * vpViewScale;

        cursorView.setPivotX(0f);
        cursorView.setPivotY(0f);
        cursorView.setScaleX(pxPerGuestPx);
        cursorView.setScaleY(pxPerGuestPx);
        cursorView.setTranslationX(cx);
        cursorView.setTranslationY(cy);
        if (cursorView.getVisibility() != VISIBLE)
            cursorView.setVisibility(VISIBLE);
    }

    @NonNull
    private RectF displayRectInContainer() {
        var rect = new RectF(0, 0, surfaceView.getWidth(), surfaceView.getHeight());
        surfaceView.getMatrix().mapRect(rect);
        rect.offset(surfaceView.getLeft(), surfaceView.getTop());
        return rect;
    }

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
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (isPhysicalMouse(event)) {
                    forwardPhysicalMouseButtons(event);
                } else {
                    // 保留触控笔侧键的原有行为。
                    short btn = mapActionButton(event.getActionButton());
                    if (btn != 0 && inputForwarder != null) {
                        inputForwarder.sendPointerButton(btn,
                            event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS);
                    }
                }
                return true;

            default:
                return false;
        }
    }

    private boolean onSurfaceHover(View v, MotionEvent event) {
        if (inputForwarder == null) return false;
        if (inputMode == InputMode.MOUSE)
            return handleMouseHover(event, surfaceView.getWidth(), surfaceView.getHeight());

        if (inputMode != InputMode.TABLET) return false;
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_MOVE
            || action == MotionEvent.ACTION_HOVER_ENTER) {
            var tf = TouchScaleCalculator.compute(v.getWidth(), v.getHeight());
            inputForwarder.sendHover(event.getX(), event.getY(), tf.scaleX, tf.scaleY);
            return true;
        }
        return false;
    }

    private boolean onContainerHover(View v, MotionEvent event) {
        if (inputForwarder == null || inputMode != InputMode.MOUSE) return false;

        float svX = event.getX() - surfaceView.getLeft() - surfaceView.getTranslationX();
        float svY = event.getY() - surfaceView.getTop() - surfaceView.getTranslationY();
        return handleMouseHover(event, surfaceView.getWidth(), surfaceView.getHeight(),
            svX, svY);
    }

    private boolean handleMouseHover(@NonNull MotionEvent event, int svW, int svH) {
        return handleMouseHover(event, svW, svH, event.getX(), event.getY());
    }

    private boolean handleMouseHover(@NonNull MotionEvent event, int svW, int svH,
                                     float x, float y) {
        if (svW <= 0 || svH <= 0) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
                hoverLastX = x;
                hoverLastY = y;
                applyPointerCapture(true);
                return true;

            case MotionEvent.ACTION_HOVER_MOVE:
                if (Float.isNaN(hoverLastX)) {
                    hoverLastX = x;
                    hoverLastY = y;
                    return true;
                }

                float scaleX = (float) guestWidth / svW;
                float scaleY = (float) guestHeight / svH;
                mouseRemX += (x - hoverLastX) * scaleX;
                mouseRemY += (y - hoverLastY) * scaleY;
                hoverLastX = x;
                hoverLastY = y;

                int dx = (int) mouseRemX;
                int dy = (int) mouseRemY;
                if (dx != 0 || dy != 0) {
                    mouseRemX -= dx;
                    mouseRemY -= dy;
                    inputForwarder.sendMouseMove(dx, dy);
                }
                return true;

            case MotionEvent.ACTION_HOVER_EXIT:
                hoverLastX = Float.NaN;
                hoverLastY = Float.NaN;
                return true;

            default:
                return false;
        }
    }

    private void applyPointerCapture(boolean capture) {
        if (pointerCaptured == capture) return;
        pointerCaptured = capture;
        if (capture) {
            surfaceView.requestFocus();
            surfaceView.requestPointerCapture();
        } else {
            surfaceView.releasePointerCapture();
        }
    }

    /**
     * 指针捕获后事件不再经过普通 hover/generic-motion 路径，因此这里必须同时处理
     * 移动和按键，不能只处理 ACTION_MOVE。
     */
    private boolean onCapturedPointerEvent(View v, MotionEvent event) {
        if (inputForwarder == null || inputMode != InputMode.MOUSE) {
            applyPointerCapture(false);
            return false;
        }

        if (!isPhysicalMouse(event)) return false;

        forwardPhysicalMouseButtons(event);
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_SCROLL) {
            inputForwarder.sendScroll(
                Math.round(event.getAxisValue(MotionEvent.AXIS_VSCROLL)),
                Math.round(event.getAxisValue(MotionEvent.AXIS_HSCROLL)));
            return true;
        }

        if (action == MotionEvent.ACTION_BUTTON_PRESS
            || action == MotionEvent.ACTION_BUTTON_RELEASE
            || action == MotionEvent.ACTION_DOWN
            || action == MotionEvent.ACTION_UP
            || action == MotionEvent.ACTION_CANCEL) {
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            float scaleX = (float) guestWidth / Math.max(1, surfaceView.getWidth());
            float scaleY = (float) guestHeight / Math.max(1, surfaceView.getHeight());
            mouseRemX += event.getX() * scaleX;
            mouseRemY += event.getY() * scaleY;

            int dx = (int) mouseRemX;
            int dy = (int) mouseRemY;
            if (dx != 0 || dy != 0) {
                mouseRemX -= dx;
                mouseRemY -= dy;
                inputForwarder.sendMouseMove(dx, dy);
            }
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

        if (keyCode == KeyEvent.KEYCODE_BACK
            && (isPhysicalMouseSource(event.getSource())
                || android.os.SystemClock.uptimeMillis() - lastMouseButtonMs
                    < MOUSE_BACK_SUPPRESS_MS)) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
            || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        if (inputForwarder != null && connected) {
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

    private void setupLayoutControllers() {
        int minAreaPx = Math.round(MIN_AREA_DP * getResources().getDisplayMetrics().density);

        viewport = new DisplayViewportController(minAreaPx,
            new DisplayViewportController.Listener() {
                @Override
                public void onViewportChanged(int baseW, int baseH, float viewScale,
                                              float offsetX, float offsetY) {
                    surfaceView.setLayoutParams(
                        new FrameLayout.LayoutParams(baseW, baseH, CENTER));
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
                keyboardGrab.setKeyboardMode(mode);

                var controller = getWindow().getInsetsController();
                if (controller != null) {
                    if (fullscreen) {
                        controller.hide(WindowInsets.Type.systemBars());
                        controller.setSystemBarsBehavior(
                            BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        controller.show(WindowInsets.Type.systemBars());
                    }
                }

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
            extraKeysPanel.setImeVisible(
                insets.isVisible(WindowInsetsCompat.Type.ime()));

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
        mainHandler.post(() -> tryShowKeyboard(imm, 15));
    }

    private void tryShowKeyboard(@NonNull InputMethodManager imm, int attemptsLeft) {
        if (attemptsLeft <= 0 || isFinishing()) return;

        keyboardInput.requestFocusFromTouch();
        keyboardInput.requestFocus();
        int flag = attemptsLeft <= 3
            ? InputMethodManager.SHOW_FORCED
            : InputMethodManager.SHOW_IMPLICIT;
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

    private void applyKeyboardMode(@NonNull KeyboardMode mode) {
        chrome.setKeyboardMode(mode);
        if (mode == KeyboardMode.SYSTEM) toggleSoftKeyboard();
        else hideSoftKeyboard();
    }

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

    private void setInputModeTo(@NonNull InputMode mode) {
        if (inputMode == mode) return;

        if (!screenInputEnabled && mode != InputMode.MOUSE)
            Toast.makeText(this, R.string.display_input_disabled_hint, Toast.LENGTH_LONG)
                .show();

        if (inputMode == InputMode.MOUSE) {
            applyPointerCapture(false);
            hoverLastX = Float.NaN;
            hoverLastY = Float.NaN;
        }

        inputMode = mode;
        pointerDriveActive = false;
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

    @Override
    public void onVMExited(UUID id, String vmName, int exitCode, JSONObject data) {
        if (id == null || !id.toString().equals(vmId)) return;

        if (exitCode != 0) {
            Log.i(TAG, fmt(
                "VM exited with %d -- keeping the console up for the exit dialog",
                exitCode));
            return;
        }

        mainHandler.post(() -> {
            if (isFinishing()) return;
            Log.i(TAG, "VM stopped; closing the display");
            finish();
        });
    }

    private void wireHardwareKeyEcho() {
        keyboardGrab.setEcho(new PhysicalKeyboardGrab.Echo() {
            @Override
            public void onKeys(@NonNull int[] codes, @NonNull int[] values) {
                for (int i = 0; i < codes.length && i < values.length; i++) {
                    int androidCode = KeyCodeMapper.evdevToAndroid(codes[i]);
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
        GamePerfHint.enterGameplay(this);
        SystemGestureGuard.enterDisplay();
        keyboardGrab.setResumed(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        GamePerfHint.exitGameplay(this);
        SystemGestureGuard.exitDisplay();
        keyboardGrab.setResumed(false);

        releasePhysicalMouseButtons();
        pointerCaptured = false;
        pointerDriveActive = false;
        hoverLastX = Float.NaN;
        hoverLastY = Float.NaN;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        releasePhysicalMouseButtons();
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
