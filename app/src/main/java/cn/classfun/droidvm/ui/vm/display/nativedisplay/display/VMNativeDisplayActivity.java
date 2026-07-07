package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static android.view.Gravity.CENTER;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
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

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;
import cn.classfun.droidvm.lib.ui.DragTouchListener;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.DirectInputSink;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.InputForwarder;
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
public final class VMNativeDisplayActivity extends AppCompatActivity {
    private static final String TAG = "VMNativeDisplay";
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_VM_ID = "vm_id";
    public static final String EXTRA_WIDTH = "display_width";
    public static final String EXTRA_HEIGHT = "display_height";
    private static final long AUTO_HIDE_DELAY_MS = 3000;

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
    private NativeKeyboardEditText keyboardInput;
    private FloatingActionButton fabMenu;
    private MaterialButton btnFullscreen;
    private DisplayExtraKeysPanel extraKeysPanel;

    private String vmName = "";
    private String vmId = "";
    private String vmKey = "";
    private int guestWidth = 1280;
    private int guestHeight = 720;

    private INativeDisplayRootService rootService;
    private DisplayProvider displayProvider;
    private InputForwarder inputForwarder;
    private DirectInputSink directSink;
    private NativeExtraKeysPanel nativeExtraKeys;
    private boolean isFullscreen = false;
    private boolean connected = false;

    // Per-attach random token: we only accept the binder broadcast carrying the nonce we requested,
    // so another app spoofing the (exported) action can't slip us a fake broker binder.
    private final String attachNonce = UUID.randomUUID().toString();
    private boolean binderReceiverRegistered = false;
    private boolean rootConnected = false;

    // The daemon broadcasts its broker binder here in response to our display_attach request.
    private final BroadcastReceiver binderReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            var bundle = intent.getBundleExtra(NativeDisplay.EXTRA_BUNDLE);
            if (bundle == null || !attachNonce.equals(bundle.getString(NativeDisplay.EXTRA_NONCE)))
                return;
            var binder = bundle.getBinder(NativeDisplay.EXTRA_BINDER);
            if (binder != null) onBinderReceived(binder);
        }
    };

    // Daemon died (e.g. restart): drop the broker binder so writes fall back to the vm_input IPC.
    private final IBinder.DeathRecipient deathRecipient = () -> mainHandler.post(() -> {
        Log.w(TAG, "daemon broker binder died");
        rootService = null;
    });

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
        setStatus(getString(R.string.native_display_connecting), R.color.vnc_status_connecting);
        showOverlay(getString(R.string.native_display_waiting));

        if (vmId.isEmpty()) {
            setStatus(getString(R.string.native_display_failed), R.color.vnc_status_error);
            showOverlay(getString(R.string.native_display_failed));
            return;
        }
        // Ask the daemon (uid=0) for its broker binder. It can't ride the JSON-RPC channel, so the
        // daemon broadcasts it back to binderReceiver (matched by attachNonce).
        registerReceiver(binderReceiver,
            new IntentFilter(NativeDisplay.BINDER_BROADCAST_ACTION), Context.RECEIVER_EXPORTED);
        binderReceiverRegistered = true;
        requestDisplayBinder(10);
    }

    // Sends display_attach; the daemon answers with a broadcast. Retries while the daemon connection
    // is still coming up, since the Activity can open just before DaemonConnection authenticates.
    private void requestDisplayBinder(int attemptsLeft) {
        if (rootConnected || isFinishing()) return;
        DaemonConnection.getInstance().buildRequest("display_attach")
            .put("nonce", attachNonce)
            .onError(e -> retryDisplayBinder(attemptsLeft))
            .onUnsuccessful(r -> retryDisplayBinder(attemptsLeft))
            .invoke();
    }

    private void retryDisplayBinder(int attemptsLeft) {
        if (attemptsLeft <= 0) {
            Log.w(TAG, "display_attach exhausted retries; daemon unavailable");
            return;
        }
        mainHandler.postDelayed(() -> requestDisplayBinder(attemptsLeft - 1), 500);
    }

    private void onBinderReceived(@NonNull IBinder binder) {
        if (rootConnected) return; // ignore duplicate broadcasts
        rootConnected = true;
        rootService = INativeDisplayRootService.Stub.asInterface(binder);
        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "linkToDeath failed", e);
        }
        onRootConnected();
    }

    private void onRootConnected() {
        if (rootService == null) return;
        // Try a direct unix-socket sink to the daemon (one write per evdev frame, no IPC
        // round-trip); on any failure it falls back to the vm_input JSON-RPC path below.
        directSink = new DirectInputSink(vmId, rootService, this::sendInputToDaemon);
        inputForwarder = new InputForwarder(directSink);
        if (nativeExtraKeys != null) nativeExtraKeys.setForwarder(inputForwarder);

        // Start the VM now that listeners are up (no-op if already running).
        DaemonConnection.getInstance().buildRequest("vm_start")
            .put("vm_id", vmId)
            .onResponse(r -> {})
            .onUnsuccessful(r -> {})
            .onError(e -> Log.w(TAG, "vm_start request failed", e))
            .invoke();

        // Display binder is looked up via the root service (servicemanager) on a bg thread.
        displayProvider = new DisplayProvider(
            surfaceView, guestWidth, guestHeight,
            () -> {
                var svc = rootService;
                if (svc == null) return null;
                try {
                    return svc.waitForDisplayBinder(vmKey);
                } catch (Exception e) {
                    return null;
                }
            },
            isConnected -> mainHandler.post(() -> onDisplayConnected(isConnected)),
            config -> mainHandler.post(() -> {
                guestWidth = config.width;
                guestHeight = config.height;
                updateAspectRatio(displayContainer.getWidth(), displayContainer.getHeight());
            })
        );
    }

    private void onDisplayConnected(boolean isConnected) {
        connected = isConnected;
        if (isConnected) {
            setStatus(fmt(getString(R.string.native_display_connected), guestWidth, guestHeight),
                R.color.vnc_status_connected);
            hideOverlay();
            updateAspectRatio(displayContainer.getWidth(), displayContainer.getHeight());
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
        surfaceView = findViewById(R.id.surface_view);
        keyboardInput = findViewById(R.id.keyboard_input);
        fabMenu = findViewById(R.id.fab_menu);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        extraKeysPanel = findViewById(R.id.extra_keys_panel);
        nativeExtraKeys = new NativeExtraKeysPanel(extraKeysPanel);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupViews() {
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        displayContainer.addOnLayoutChangeListener((
            v, l, t, r, b, ol, ot, or2, ob
        ) -> {
            int cw = r - l, ch = b - t;
            if (cw > 0 && ch > 0) v.post(() -> updateAspectRatio(cw, ch));
        });
        surfaceView.setOnTouchListener(this::onSurfaceTouch);
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
    // character to its key event sequence and forward as evdev.
    private void forwardText(@NonNull CharSequence text) {
        if (inputForwarder == null || !connected) return;
        KeyEvent[] events = keyCharacterMap.getEvents(text.toString().toCharArray());
        if (events == null) return;
        // Wrap with the extra-keys panel modifiers so e.g. Ctrl+(typed key) reaches the guest.
        nativeExtraKeys.applyModifiers(true);
        for (KeyEvent e : events) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                inputForwarder.sendKeyEvent(e.getKeyCode(), true);
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                inputForwarder.sendKeyEvent(e.getKeyCode(), false);
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

    private boolean onSurfaceTouch(View v, MotionEvent event) {
        if (inputForwarder == null || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        // The SurfaceView is sized to the guest aspect ratio, so offsets are zero and scale is
        // simply guest/view per axis.
        var tf = TouchScaleCalculator.compute(guestWidth, guestHeight, v.getWidth(), v.getHeight());
        inputForwarder.sendTouchEvent(event, tf.scaleX, tf.scaleY);
        return true;
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

    private void updateAspectRatio(int containerW, int containerH) {
        if (containerW <= 0 || containerH <= 0 || guestWidth <= 0 || guestHeight <= 0) return;
        float vmAspect = (float) guestWidth / guestHeight;
        float containerAspect = (float) containerW / containerH;
        int viewW, viewH;
        if (vmAspect > containerAspect) {
            viewW = containerW;
            viewH = Math.round(containerW / vmAspect);
        } else {
            viewH = containerH;
            viewW = Math.round(containerH * vmAspect);
        }
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(viewW, viewH, CENTER));
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
        if (imm != null) tryShowKeyboard(imm, 10);
    }

    // Drive a real (invisible) EditText: a SurfaceView is an unreliable IME target on some ROMs.
    // The popup menu relinquishes window focus right before this runs, so the editor isn't yet
    // "served" by the IMM and showSoftInput() is silently ignored (no ResultReceiver callback
    // either). Retry on a short delay until the input connection is established; force as a last
    // resort.
    private void tryShowKeyboard(@NonNull InputMethodManager imm, int attemptsLeft) {
        keyboardInput.requestFocus();
        if (imm.showSoftInput(keyboardInput, 0)) return;
        if (attemptsLeft > 0) {
            mainHandler.postDelayed(() -> tryShowKeyboard(imm, attemptsLeft - 1), 60);
        }
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        var controller = getWindow().getInsetsController();
        if (controller == null) return;
        if (isFullscreen) {
            toolbar.setVisibility(GONE);
            statusBar.setVisibility(GONE);
            extraKeysPanel.setVisibility(GONE);
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            toolbar.setVisibility(VISIBLE);
            statusBar.setVisibility(VISIBLE);
            extraKeysPanel.animateIn();
            controller.show(WindowInsets.Type.systemBars());
        }
    }

    private void showFabMenu() {
        var popup = new MaterialMenu(this, fabMenu);
        popup.inflate(R.menu.menu_native_display_menu);
        popup.setOnMenuItemClickListener(this::onMenuItemClicked);
        popup.show();
    }

    private boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_keyboard) {
            toggleSoftKeyboard();
            return true;
        } else if (id == R.id.menu_extra_keys) {
            extraKeysPanel.setVisibleAnimated(extraKeysPanel.getVisibility() != VISIBLE);
            return true;
        } else if (id == R.id.menu_fullscreen) {
            toggleFullscreen();
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        extraKeysPanel.stopKeyRepeat();
        if (displayProvider != null) {
            displayProvider.shutdown();
            displayProvider = null;
        }
        if (inputForwarder != null) {
            inputForwarder.close();
            inputForwarder = null;
        }
        if (directSink != null) {
            directSink.close();
            directSink = null;
        }
        if (binderReceiverRegistered) {
            try {
                unregisterReceiver(binderReceiver);
            } catch (Exception ignored) {
            }
            binderReceiverRegistered = false;
        }
        if (rootService != null) {
            try {
                rootService.asBinder().unlinkToDeath(deathRecipient, 0);
            } catch (Exception ignored) {
            }
        }
        rootService = null;
    }

    @NonNull
    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
