package cn.classfun.droidvm.ui.vm.display.vnc.display;

import static android.view.Gravity.BOTTOM;
import static android.view.Gravity.CENTER;
import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.view.Gravity.CENTER_VERTICAL;
import static android.view.Gravity.END;
import static android.view.Gravity.START;
import static android.view.Gravity.TOP;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.ui.DragTouchListener;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.vm.display.base.DaemonDisplayAttach;
import cn.classfun.droidvm.ui.vm.display.base.DisplayChromeController;
import cn.classfun.droidvm.ui.vm.display.base.DisplaySource;
import cn.classfun.droidvm.ui.vm.display.base.DisplayViewportController;
import cn.classfun.droidvm.ui.vm.display.base.PointerGestureTranslator;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.DirectInputSink;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.EvdevEncoder;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;
import cn.classfun.droidvm.ui.vm.display.vnc.base.BaseVncActivity;

public final class VMVncDisplayActivity extends BaseVncActivity {
    private static final long OP_LABEL_HIDE_DELAY_MS = 2000;
    private static final String PREFS_NAME = "droidvm_prefs";
    private static final String KEY_INPUT_MODE = "display_input_mode";
    // RFB pointer button-mask bits (the crosvm VNC server is fixed to tablet mode: an absolute
    // pointer with these buttons plus scroll pulses).
    private static final int MASK_LEFT = 1;
    private static final int MASK_MIDDLE = 2;
    private static final int MASK_RIGHT = 4;
    private static final int MASK_SCROLL_UP = 8;
    private static final int MASK_SCROLL_DOWN = 16;

    // Per-mode input routing: whatever the VNC channel natively has goes over RFB (TABLET's
    // absolute pointer + the keyboard); the rest goes to the crosvm --input evdev devices via the
    // daemon (MOUSE = relative motion the guest renders a cursor for, TOUCH = raw multi-touch).
    private enum InputMode {TOUCH, MOUSE, TABLET}
    private LinearLayout statusBar;
    private MaterialButton btnFullscreen;
    private FrameLayout displayContainer;
    private FloatingActionButton fabMenu;
    private TextView operationLabel;
    private InputMode inputMode = InputMode.TOUCH;
    private SharedPreferences prefs;

    private InputForwarder inputForwarder;
    private PointerGestureTranslator gestureTranslator;
    private int rfbMask;                 // current RFB button mask (tablet mode)
    private int rfbLastX, rfbLastY;      // last absolute pointer position sent, fb px
    private float mouseRemX, mouseRemY;  // fractional remainders of relative mouse motion
    // Single sources of truth for viewport geometry (fit/zoom/pan across display-area changes)
    // and chrome visibility (fullscreen / extra keys). See the controller classes for the rules.
    private DisplayViewportController viewport;
    private DisplayChromeController chrome;
    // Display areas smaller than this (e.g. landscape with a tall IME) freeze the viewport
    // instead of re-laying it out; see DisplayViewportController.
    private static final int MIN_AREA_DP = 96;
    // Last mouse right/middle-button activity: any BACK key arriving shortly after is the
    // framework's (or OEM's) right-click fallback and must not navigate away from the VM.
    private long lastMouseButtonMs;
    private static final long MOUSE_BACK_SUPPRESS_MS = 800;

    // Daemon broker binder plumbing (shared DaemonDisplayAttach): the direct sink turns each
    // evdev frame into one binder call instead of the (much slower) vm_input JSON-RPC round-trip.
    // Input works immediately on the RPC fallback and upgrades in place once the binder arrives;
    // written on the main thread, read on the InputForwarder worker (hence volatile).
    private volatile DirectInputSink directSink;
    private DaemonDisplayAttach displayAttach;
    // Display-source adapter: framebuffer events flow through the shared DisplaySource interface.
    private VncBitmapSource displaySource;

    private final Runnable hideOperationLabel = () -> {
        if (operationLabel != null) operationLabel.setVisibility(GONE);
    };

    // Unified MOUSE/TABLET gestures. TABLET lands on the RFB channel (the VNC server's fixed
    // absolute-tablet pointer); MOUSE lands on the crosvm relative-mouse device via vm_input.
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
                rfbMove(Math.round(xGuest), Math.round(yGuest));
            }

            @Override
            public void onLeftButton(boolean down, float xGuest, float yGuest) {
                if (inputMode == InputMode.TABLET) {
                    rfbButton(MASK_LEFT, down, Math.round(xGuest), Math.round(yGuest));
                } else if (inputForwarder != null) {
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
                if (inputMode == InputMode.TABLET) {
                    int x = Math.round(xGuest), y = Math.round(yGuest);
                    rfbButton(MASK_RIGHT, true, x, y);
                    rfbButton(MASK_RIGHT, false, x, y);
                } else if (inputForwarder != null) {
                    inputForwarder.sendPointerButton(EvdevEncoder.BTN_RIGHT, true);
                    inputForwarder.sendPointerButton(EvdevEncoder.BTN_RIGHT, false);
                }
            }

            @Override
            public void onScroll(int vNotches, int hNotches) {
                if (inputMode == InputMode.TABLET) {
                    rfbScroll(vNotches);
                } else if (inputForwarder != null) {
                    inputForwarder.sendScroll(vNotches, hNotches);
                }
            }

            @Override
            public void onZoomPan(float scaleFactor, float dxView, float dyView,
                                  float focusX, float focusY) {
                if (viewport != null) viewport.onZoomPan(scaleFactor, dxView, dyView);
            }
        };

    // ---- RFB tablet-pointer helpers (absolute position + button mask) ----

    private void rfbMove(int x, int y) {
        if (vncClient == null || !vncClient.isConnected() || fbWidth <= 0) return;
        rfbLastX = max(0, min(x, fbWidth - 1));
        rfbLastY = max(0, min(y, fbHeight - 1));
        vncClient.sendPointer(rfbLastX, rfbLastY, rfbMask);
    }

    private void rfbButton(int maskBit, boolean down, int x, int y) {
        if (vncClient == null || !vncClient.isConnected() || fbWidth <= 0) return;
        rfbLastX = max(0, min(x, fbWidth - 1));
        rfbLastY = max(0, min(y, fbHeight - 1));
        rfbMask = down ? (rfbMask | maskBit) : (rfbMask & ~maskBit);
        vncClient.sendPointer(rfbLastX, rfbLastY, rfbMask);
    }

    /** RFB has no wheel axis; each notch is a scroll-button press/release pulse. */
    private void rfbScroll(int vNotches) {
        if (vncClient == null || !vncClient.isConnected() || vNotches == 0) return;
        int bit = vNotches > 0 ? MASK_SCROLL_UP : MASK_SCROLL_DOWN;
        for (int i = 0; i < Math.abs(vNotches); i++) {
            vncClient.sendPointer(rfbLastX, rfbLastY, rfbMask | bit);
            vncClient.sendPointer(rfbLastX, rfbLastY, rfbMask);
        }
    }

    // Ships evdev records for MOUSE/TOUCH modes to the daemon, which owns the crosvm --input
    // sockets. Runs on the InputForwarder worker thread (synchronous request keeps ordering).
    private boolean sendInputToDaemon(int channel, @NonNull byte[] data) {
        try {
            var req = new JSONObject();
            req.put("command", "vm_input");
            req.put("vm_id", vmId);
            req.put("channel", channel);
            req.put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
            var resp = DaemonConnection.getInstance().request(req);
            return resp.optBoolean("delivered", false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_vm_vnc_display;
    }

    @Override
    protected String getActivityTitle() {
        return vmName.isEmpty() ? getString(R.string.vnc_display_title) : vmName;
    }

    @Override
    protected void onBindExtraViews() {
        statusBar = findViewById(R.id.status_bar);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        displayContainer = findViewById(R.id.display_container);
        fabMenu = findViewById(R.id.fab_menu);
        operationLabel = findViewById(R.id.tv_operation);
    }

    @Override
    protected void onSetupActivity() {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        inputMode = InputMode.values()[prefs.getInt(KEY_INPUT_MODE, 0)];
        setupCutoutMode();
        setupLayoutControllers();
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        // The container's layout size IS the display area: chrome visibility, IME and rotation
        // all funnel into it through normal layout. The viewport handles degenerate sizes itself.
        displayContainer.addOnLayoutChangeListener((
            v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom
        ) -> {
            int cw = right - left, ch = bottom - top;
            v.post(() -> viewport.setArea(cw, ch));
        });
        setupOperationLabel();
        setupDisplayTouch();
        setupFab();
        // Delegating sink: the direct daemon-binder path once attached, the vm_input JSON-RPC
        // until then (and again if the binder dies). DirectInputSink falls back internally on
        // any per-write failure, so input never drops during the upgrade.
        inputForwarder = new InputForwarder((channel, data) -> {
            var sink = directSink;
            return sink != null ? sink.write(channel, data) : sendInputToDaemon(channel, data);
        });
        gestureTranslator = new PointerGestureTranslator(mainHandler, gestureListener);
        // Hardware mouse/stylus: hover is TABLET-only (RFB absolute move); wheel and right/middle
        // buttons route per mode (tablet -> RFB mask, mouse -> crosvm mouse device).
        ivDisplay.setOnHoverListener(this::onDisplayHover);
        ivDisplay.setOnGenericMotionListener(this::onDisplayGenericMotion);
        // Also on the container: a right-click over the letterbox area (outside the image) must
        // still be consumed or the framework synthesizes BACK from it.
        displayContainer.setOnGenericMotionListener(this::onDisplayGenericMotion);
        // Keep the display area out of the system-gesture zones so multi-finger gestures
        // (two-finger right-click/scroll, three-finger zoom) don't trip OEM gestures.
        displayContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) ->
            v.setSystemGestureExclusionRects(java.util.Collections.singletonList(
                new android.graphics.Rect(0, 0, r - l, b - t))));
        applyInputMode();

        if (!vmId.isEmpty()) {
            displayAttach = new DaemonDisplayAttach(this, mainHandler,
                new DaemonDisplayAttach.Listener() {
                    @Override
                    public void onAttached(@NonNull INativeDisplayRootService service) {
                        directSink = new DirectInputSink(vmId, service, VMVncDisplayActivity.this::sendInputToDaemon);
                    }

                    @Override
                    public void onLost() {
                        // Drop the direct sink so writes go back to the vm_input RPC.
                        directSink = null;
                    }
                });
            displayAttach.start();
        }
    }

    private boolean onDisplayHover(View v, MotionEvent event) {
        if (inputMode != InputMode.TABLET) return false;
        if (fbWidth <= 0 || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER) {
            rfbMove(Math.round(event.getX() * fbWidth / v.getWidth()),
                Math.round(event.getY() * fbHeight / v.getHeight()));
            return true;
        }
        return false;
    }

    // Button presses are ALWAYS consumed (every mode, letterbox included) - an unhandled
    // BUTTON_SECONDARY press is what makes the framework synthesize a BACK key.
    private boolean onDisplayGenericMotion(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_SCROLL: {
                int vN = Math.round(event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                int hN = Math.round(event.getAxisValue(MotionEvent.AXIS_HSCROLL));
                if (inputMode == InputMode.TABLET) rfbScroll(vN);
                else if (inputForwarder != null) inputForwarder.sendScroll(vN, hN);
                return true;
            }
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE: {
                lastMouseButtonMs = android.os.SystemClock.uptimeMillis();
                boolean down = event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS;
                // Map view coords to fb px; events from the container carry the letterbox offset.
                float lx = event.getX(), ly = event.getY();
                if (v == displayContainer) {
                    lx -= ivDisplay.getLeft();
                    ly -= ivDisplay.getTop();
                }
                int ivW = ivDisplay.getWidth(), ivH = ivDisplay.getHeight();
                int x = ivW > 0 && fbWidth > 0 ? Math.round(lx * fbWidth / ivW) : rfbLastX;
                int y = ivH > 0 && fbHeight > 0 ? Math.round(ly * fbHeight / ivH) : rfbLastY;
                switch (event.getActionButton()) {
                    case MotionEvent.BUTTON_SECONDARY:
                    case MotionEvent.BUTTON_STYLUS_PRIMARY:
                        if (inputMode == InputMode.TABLET) rfbButton(MASK_RIGHT, down, x, y);
                        else if (inputForwarder != null)
                            inputForwarder.sendPointerButton(EvdevEncoder.BTN_RIGHT, down);
                        break;
                    case MotionEvent.BUTTON_TERTIARY:
                        if (inputMode == InputMode.TABLET) rfbButton(MASK_MIDDLE, down, x, y);
                        else if (inputForwarder != null)
                            inputForwarder.sendPointerButton(EvdevEncoder.BTN_MIDDLE, down);
                        break;
                    default:
                        break;
                }
                return true;
            }
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull android.view.KeyEvent event) {
        // OEM-injected right-click fallback BACK may claim a keyboard/virtual source, which the
        // base class's mouse-source check misses; the timestamp catches it regardless.
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK
            && android.os.SystemClock.uptimeMillis() - lastMouseButtonMs
                < MOUSE_BACK_SUPPRESS_MS)
            return true;
        return super.dispatchKeyEvent(event);
    }

    private void setupCutoutMode() {
        var params = getWindow().getAttributes();
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(params);
    }

    // Wires the viewport controller (single writer of the display-image geometry), the chrome
    // controller (single writer of toolbar/status bar/extra keys/system bars visibility) and the
    // window-insets listener that turns system bars + IME into content padding, which in turn
    // sizes the display container.
    private void setupLayoutControllers() {
        int minAreaPx = Math.round(MIN_AREA_DP * getResources().getDisplayMetrics().density);
        viewport = new DisplayViewportController(minAreaPx,
            new DisplayViewportController.Listener() {
                @Override
                public void onViewportChanged(int baseW, int baseH, float viewScale,
                                              float offsetX, float offsetY) {
                    ivDisplay.setLayoutParams(new FrameLayout.LayoutParams(baseW, baseH, CENTER));
                    ivDisplay.setScaleX(viewScale);
                    ivDisplay.setScaleY(viewScale);
                    ivDisplay.setTranslationX(offsetX);
                    ivDisplay.setTranslationY(offsetY);
                }

                @Override
                public void onGuestResizeWanted(int areaW, int areaH) {
                    // Auto-resize Guest Display: no guest-side channel on this path yet.
                }
            });

        displaySource = new VncBitmapSource(new DisplaySource.Callbacks() {
            @Override
            public void onContentSize(int width, int height) {
                viewport.setContentSize(width, height);
            }

            @Override
            public void onStateChanged(@NonNull DisplaySource.State state) {
                // Status text and overlay are handled by BaseVncActivity's own status plumbing.
            }
        });

        chrome = new DisplayChromeController(true, (fullscreen, extraKeysVisible) -> {
            toolbar.setVisibility(fullscreen ? GONE : VISIBLE);
            statusBar.setVisibility(fullscreen ? GONE : VISIBLE);
            extraKeysPanel.setVisibleAnimated(extraKeysVisible);
            var controller = getWindow().getInsetsController();
            if (controller != null) {
                if (fullscreen) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
            // Re-request insets so the content padding (and thus the display area) updates in
            // the same pass as the visibility changes.
            ViewCompat.requestApplyInsets(findViewById(android.R.id.content));
        });

        var content = (ViewGroup) findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean fullscreen = chrome != null && chrome.isFullscreen();
            int top = fullscreen ? 0 : sysBars.top;
            int bottom = Math.max(fullscreen ? 0 : sysBars.bottom, ime.bottom);
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void setupOperationLabel() {
        if (operationLabel == null) return;
        var bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0x80000000);
        operationLabel.setBackground(bg);
    }

    @Override
    protected void onFramebufferReady(int width, int height) {
        displaySource.dispatchContentSize(width, height);
    }

    @Override
    protected void onBitmapUpdated(@NonNull Bitmap bitmap) {
        ivDisplay.setImageBitmap(bitmap);
    }

    @Override
    protected void onDestroyExtra() {
        mainHandler.removeCallbacks(hideOperationLabel);
        if (inputForwarder != null) inputForwarder.close();
        var sink = directSink;
        directSink = null;
        if (sink != null) sink.close();
        if (displayAttach != null) {
            displayAttach.stop();
            displayAttach = null;
        }
    }

    // Routes on-screen touches by input mode. TOUCH listens on ivDisplay, which is laid out to
    // the framebuffer's aspect (DisplayViewportController), so view coords scale straight to the
    // wire range. MOUSE/TABLET listen on the whole container; the gesture translator pins the
    // coordinate-carrying gestures to the rendered image rect.
    private boolean onDisplayTouch(View v, MotionEvent event) {
        if (fbWidth <= 0 || fbHeight <= 0) return false;
        int ivW = ivDisplay.getWidth(), ivH = ivDisplay.getHeight();
        if (ivW <= 0 || ivH <= 0) return false;
        // A hardware-mouse right/middle press also arrives on the touch stream (ACTION_DOWN with
        // the button in buttonState). Those are delivered by the generic-motion handler; keep them
        // out of the tap/gesture path (else right-click doubles as a left tap) but consume them so
        // the framework doesn't synthesize a BACK key from an unhandled right-click.
        if ((event.getSource() & android.view.InputDevice.SOURCE_MOUSE) != 0
            && (event.getButtonState() & (MotionEvent.BUTTON_SECONDARY
                | MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
            lastMouseButtonMs = android.os.SystemClock.uptimeMillis();
            return true;
        }
        switch (inputMode) {
            case TABLET:
            case MOUSE:
                // TABLET rides RFB, whose pointer coordinates are framebuffer px (crosvm's VNC
                // server normalizes them itself); MOUSE REL deltas are guest px. Both therefore
                // map the display rect onto the framebuffer size, NOT the normalized ABS range.
                return gestureTranslator != null && gestureTranslator.onTouchEvent(
                    event, displayRectInContainer(), fbWidth, fbHeight);
            case TOUCH:
            default:
                if (inputForwarder == null) return false;
                // The evdev multi-touch device advertises the fixed normalized ABS range
                // (--input multi-touch with no width/height); scale view coords to that range.
                inputForwarder.sendTouchEvent(event,
                    (float) EvdevEncoder.NORMALIZED_ABS_MAX / ivW,
                    (float) EvdevEncoder.NORMALIZED_ABS_MAX / ivH);
                return true;
        }
    }

    // Where the framebuffer is rendered, in container coordinates: the letterbox-fitted image
    // bounds mapped through the viewport's current zoom/pan transform.
    @NonNull
    private RectF displayRectInContainer() {
        var rect = new RectF(0, 0, ivDisplay.getWidth(), ivDisplay.getHeight());
        ivDisplay.getMatrix().mapRect(rect);
        rect.offset(ivDisplay.getLeft(), ivDisplay.getTop());
        return rect;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDisplayTouch() {
        ivDisplay.setTextCommitListener(createTextCommitListener());
    }

    @SuppressLint("ClickableViewAccessibility")
    private void applyInputMode() {
        ivDisplay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (operationLabel != null) operationLabel.setVisibility(GONE);
        if (inputMode == InputMode.MOUSE || inputMode == InputMode.TABLET) {
            // Whole-container gesture surface: MOUSE is a borderless touchpad; TABLET pins the
            // coordinate-carrying gestures to the rendered image inside the translator, so
            // multi-finger gestures still work from the letterbox.
            ivDisplay.setClickable(false);
            ivDisplay.setOnTouchListener(null);
            displayContainer.setClickable(true);
            displayContainer.setOnClickListener(null);
            displayContainer.setOnTouchListener(this::onDisplayTouch);
        } else {
            ivDisplay.setClickable(true);
            ivDisplay.setOnTouchListener(this::onDisplayTouch);
            displayContainer.setOnTouchListener(null);
            displayContainer.setClickable(true);
            displayContainer.setOnClickListener(v -> toggleSoftKeyboard());
        }
        if (gestureTranslator != null) {
            gestureTranslator.setAbsolute(inputMode == InputMode.TABLET);
            gestureTranslator.reset();
        }
        if (inputForwarder != null)
            inputForwarder.setInputMode(toSharedMode(inputMode));
    }

    private void setInputMode(InputMode mode) {
        if (inputMode == mode) return;
        inputMode = mode;
        prefs.edit().putInt(KEY_INPUT_MODE, mode.ordinal()).apply();
        viewport.resetToFit();
        applyInputMode();
    }

    /** This activity's private mode enum mapped onto the shared one InputForwarder understands. */
    private static cn.classfun.droidvm.ui.vm.display.base.InputMode toSharedMode(InputMode m) {
        switch (m) {
            case MOUSE:
                return cn.classfun.droidvm.ui.vm.display.base.InputMode.MOUSE;
            case TABLET:
                return cn.classfun.droidvm.ui.vm.display.base.InputMode.TABLET;
            default:
                return cn.classfun.droidvm.ui.vm.display.base.InputMode.TOUCH;
        }
    }

    private void showOperation(int resId) {
        if (operationLabel == null) return;
        operationLabel.setText(resId);
        operationLabel.setVisibility(VISIBLE);
        mainHandler.removeCallbacks(hideOperationLabel);
        mainHandler.postDelayed(hideOperationLabel, OP_LABEL_HIDE_DELAY_MS);
    }

    private void toggleFullscreen() {
        chrome.toggleFullscreen();
    }

    private void toggleExtraKeys() {
        chrome.toggleExtraKeys();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupFab() {
        var listener = new DragTouchListener(this, this::showFabMenu);
        fabMenu.setOnTouchListener(listener);
    }

    private void showFabMenu() {
        var popup = new MaterialMenu(this, fabMenu);
        popup.inflate(R.menu.menu_vnc_display_menu);
        popup.setHeaderView(buildInputModeHeader(popup));
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
            setInputMode(checkedId == R.id.mode_mouse ? InputMode.MOUSE
                : checkedId == R.id.mode_tablet ? InputMode.TABLET : InputMode.TOUCH);
            popup.dismiss();
        });
        return group;
    }

    @Override
    protected boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_extra_keys) {
            toggleExtraKeys();
            return true;
        } else if (id == R.id.menu_fullscreen) {
            toggleFullscreen();
            return true;
        }
        return super.onMenuItemClicked(item);
    }
}
