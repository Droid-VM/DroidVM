package cn.classfun.droidvm.ui.vm.display.vnc.base;

import static android.content.DialogInterface.BUTTON_NEUTRAL;
import static android.graphics.drawable.GradientDrawable.OVAL;
import static android.view.KeyEvent.*;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT;
import static org.yaml.snakeyaml.util.UriEncoder.encode;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static cn.classfun.droidvm.lib.utils.NetUtils.resolveAddress;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.ui.vm.display.base.X11Keymap.*;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.ExecutorService;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.vnc.input.VncExtraKeysPanel;

public abstract class BaseVncActivity extends AppCompatActivity {
    protected final String TAG = getClass().getSimpleName();
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_VM_ID = "vm_id";
    protected static final int DEFAULT_PORT = 5900;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 2000;
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected final ExecutorService executor = newSingleThreadExecutor(this::msgLoopThread);
    protected VncClient vncClient;
    protected MaterialToolbar toolbar;
    protected View statusIndicator;
    protected TextView tvStatus;
    protected TextView tvConnectingMessage;
    protected LinearLayout overlayConnecting;
    protected VncDisplayView ivDisplay;
    protected DisplayExtraKeysPanel extraKeysPanel;
    protected VncExtraKeysPanel vncExtraKeys;
    protected String vmName = "";
    protected String vmId = "";
    protected String vncHost = "127.0.0.1";
    protected int vncPort = DEFAULT_PORT;
    protected String vncPassword = null;
    protected volatile boolean running = false;
    protected volatile boolean needsRefresh = false;
    private int reconnectAttempt = 0;
    protected int fbWidth, fbHeight;
    protected Bitmap displayBitmap;
    protected final Object bitmapLock = new Object();
    protected VncStatus status = VncStatus.CONNECTING;

    protected enum VncStatus {
        CONNECTING,
        CONNECTED,
        ERROR
    }

    @SuppressWarnings("unused")
    protected abstract int getContentLayoutId();

    @SuppressWarnings("unused")
    protected abstract String getActivityTitle();

    @SuppressWarnings("unused")
    protected abstract void onBindExtraViews();

    @SuppressWarnings("unused")
    protected abstract void onSetupActivity();

    @SuppressWarnings("unused")
    protected abstract void onFramebufferReady(int width, int height);

    @SuppressWarnings("unused")
    protected abstract void onBitmapUpdated(@NonNull Bitmap bitmap);

    @SuppressWarnings("unused")
    protected void onStatusChanged(String text, VncStatus status) {
    }

    @SuppressWarnings("unused")
    protected void onDestroyExtra() {
    }

    @SuppressWarnings("unused")
    protected void onVncClientCreated() {
    }

    protected void onClearDisplay() {
        ivDisplay.setImageBitmap(null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(getContentLayoutId());
        var intent = getIntent();
        vmName = intent.getStringExtra(EXTRA_VM_NAME);
        if (vmName == null) vmName = "";
        vmId = intent.getStringExtra(EXTRA_VM_ID);
        if (vmId == null) vmId = "";
        bindViews();
        setupToolbar();
        onSetupActivity();
        vncExtraKeys = new VncExtraKeysPanel(extraKeysPanel);
        fetchVncInfoAndConnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onDestroyExtra();
        extraKeysPanel.stopKeyRepeat();
        running = false;
        if (vncClient != null) vncClient.requestStop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, SECONDS))
                executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (vncClient != null) {
            vncClient.disconnect();
            vncClient = null;
        }
        onClearDisplay();
        synchronized (bitmapLock) {
            if (displayBitmap != null) {
                displayBitmap.recycle();
                displayBitmap = null;
            }
        }
    }

    private void bindViews() {
        toolbar = findViewById(R.id.toolbar);
        statusIndicator = findViewById(R.id.status_indicator);
        tvStatus = findViewById(R.id.tv_status);
        tvConnectingMessage = findViewById(R.id.tv_connecting_message);
        overlayConnecting = findViewById(R.id.overlay_connecting);
        ivDisplay = findViewById(R.id.iv_display);
        extraKeysPanel = findViewById(R.id.extra_keys_panel);
        onBindExtraViews();
    }

    private void setupToolbar() {
        toolbar.setTitle(getActivityTitle());
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    @NonNull
    private Thread msgLoopThread(Runnable r) {
        var t = new Thread(r, fmt("%s-loop", TAG));
        t.setDaemon(true);
        return t;
    }

    protected void fetchVncInfoAndConnect() {
        setStatus(getString(R.string.vnc_display_connecting), VncStatus.CONNECTING);
        showConnectingOverlay(getString(R.string.vnc_display_connecting));
        if (vmId.isEmpty()) {
            Log.e(TAG, "No VM ID provided");
            setStatus(getString(R.string.vnc_display_connect_failed), VncStatus.ERROR);
            showConnectingOverlay(getString(R.string.vnc_display_connect_failed));
            return;
        }
        DaemonConnection.OnError err = e -> {
            Log.e(TAG, "Failed to query VNC info", e);
            mainHandler.post(() -> {
                setStatus(getString(R.string.vnc_display_connect_failed), VncStatus.ERROR);
                showConnectingOverlay(getString(R.string.vnc_display_connect_failed));
            });
        };
        DaemonConnection.OnUnsuccessful f = resp -> {
            var msg = resp.optString("message", "Failed to get VNC info");
            Log.e(TAG, msg);
            mainHandler.post(() -> {
                setStatus(msg, VncStatus.ERROR);
                showConnectingOverlay(msg);
            });
        };
        DaemonConnection.OnResponse res = resp -> {
            vncHost = resp.optString("host", "127.0.0.1");
            vncPort = resp.optInt("port", DEFAULT_PORT);
            vncPassword = resp.optString("password", "");
            if (vncPassword.isEmpty()) vncPassword = null;
            mainHandler.post(this::startVnc);
        };
        DaemonConnection.getInstance().buildRequest("vm_vnc_info")
            .put("vm_id", vmId)
            .onResponse(res)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    private class VncNativeCallback implements VncClient.NativeCallback {
        @Override
        public void onFramebufferResized(int width, int height) {
            Log.i(TAG, fmt("FB resized %dx%d", width, height));
            fbWidth = width;
            fbHeight = height;
            Bitmap oldBitmap;
            synchronized (bitmapLock) {
                oldBitmap = displayBitmap;
                displayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            mainHandler.post(() -> {
                if (oldBitmap != null) {
                    onClearDisplay();
                    if (!oldBitmap.isRecycled()) oldBitmap.recycle();
                }
                setStatus(getString(R.string.vnc_display_connected, width, height), VncStatus.CONNECTED);
                hideConnectingOverlay();
                onFramebufferReady(width, height);
            });
        }

        @Override
        public void onFramebufferUpdated(int x, int y, int w, int h) {
            needsRefresh = true;
        }
    }

    private void startVnc() {
        if (executor.isShutdown()) {
            Log.w(TAG, "Executor already shut down, skipping startVnc");
            return;
        }
        setStatus(getString(R.string.vnc_display_connecting), VncStatus.CONNECTING);
        showConnectingOverlay(getString(R.string.vnc_display_connecting));
        executor.submit(() -> {
            try {
                vncClient = new VncClient();
                vncExtraKeys.setVncClient(vncClient);
                mainHandler.post(this::onVncClientCreated);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create VncClient", e);
                mainHandler.post(this::scheduleAutoReconnect);
                return;
            }
            var callback = new VncNativeCallback();
            boolean ok = vncClient.connect(vncHost, vncPort, vncPassword, callback);
            if (!ok) {
                Log.e(TAG, "VNC connect failed");
                mainHandler.post(this::scheduleAutoReconnect);
                return;
            }
            running = true;
            reconnectAttempt = 0;
            mainHandler.post(() -> {
                int w = vncClient.getWidth();
                int h = vncClient.getHeight();
                if (w > 0 && h > 0) {
                    setStatus(getString(R.string.vnc_display_connected, w, h), VncStatus.CONNECTED);
                    hideConnectingOverlay();
                }
            });
            messageLoop();
        });
    }

    private void messageLoop() {
        var client = vncClient;
        while (running && client != null && client.isConnected()) {
            if (client.processMessages() < 0) break;
            if (needsRefresh) {
                needsRefresh = false;
                refreshDisplay();
            }
        }
        boolean wasRunning = running;
        running = false;
        Log.i(TAG, "message loop ended");
        if (wasRunning) {
            mainHandler.post(this::scheduleAutoReconnect);
        }
    }

    private void scheduleAutoReconnect() {
        if (executor.isShutdown()) {
            Log.w(TAG, "Executor already shut down, skipping reconnect");
            return;
        }
        reconnectAttempt++;
        if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            var msg = getString(R.string.vnc_display_reconnect_failed,
                MAX_RECONNECT_ATTEMPTS);
            setStatus(msg, VncStatus.ERROR);
            showConnectingOverlay(msg);
            return;
        }
        Log.i(TAG, fmt("Scheduling auto-reconnect attempt %d/%d",
            reconnectAttempt, MAX_RECONNECT_ATTEMPTS));
        var msg = getString(R.string.vnc_display_reconnecting,
            reconnectAttempt, MAX_RECONNECT_ATTEMPTS);
        setStatus(msg, VncStatus.CONNECTING);
        showConnectingOverlay(msg);
        onClearDisplay();
        synchronized (bitmapLock) {
            if (displayBitmap != null) {
                displayBitmap.recycle();
                displayBitmap = null;
            }
        }
        fbWidth = 0;
        fbHeight = 0;
        executor.submit(() -> {
            if (vncClient != null) {
                vncClient.disconnect();
                vncClient = null;
            }
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            mainHandler.post(this::fetchVncInfoAndConnect);
        });
    }

    private void refreshDisplay() {
        var client = vncClient;
        synchronized (bitmapLock) {
            if (displayBitmap == null || client == null) return;
            client.copyPixels(displayBitmap);
        }
        mainHandler.post(() -> {
            synchronized (bitmapLock) {
                if (displayBitmap != null && !displayBitmap.isRecycled()) {
                    onBitmapUpdated(displayBitmap);
                }
            }
        });
    }

    protected void setStatus(String text, VncStatus newStatus) {
        int color;
        if (newStatus == this.status) return;
        switch (newStatus) {
            case CONNECTED:
                color = getColor(R.color.vnc_status_connected);
                break;
            case ERROR:
                color = getColor(R.color.vnc_status_error);
                break;
            case CONNECTING:
                color = getColor(R.color.vnc_status_connecting);
                break;
            default:
                return;
        }
        tvStatus.setText(text);
        this.status = newStatus;
        var indicator = new GradientDrawable();
        indicator.setShape(OVAL);
        indicator.setColor(color);
        statusIndicator.setBackground(indicator);
        onStatusChanged(text, newStatus);
    }

    protected void showConnectingOverlay(String message) {
        overlayConnecting.setVisibility(VISIBLE);
        tvConnectingMessage.setText(message);
    }

    protected void hideConnectingOverlay() {
        overlayConnecting.setVisibility(GONE);
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KEYCODE_VOLUME_UP || keyCode == KEYCODE_VOLUME_DOWN)
            return super.dispatchKeyEvent(event);
        int keysym = androidKeyToXKeysym(keyCode);
        if (keysym != 0 && vncClient != null && vncClient.isConnected()) {
            boolean modifier = isModifierKey(keyCode);
            int action = event.getAction();
            int metaState = event.getMetaState();
            int baseKeysym = shiftedSymbolToBase(keysym);
            if (baseKeysym != 0) {
                keysym = baseKeysym;
                metaState = (metaState | META_SHIFT_ON) & ~META_SHIFT_MASK | META_SHIFT_ON;
            }
            if (action == KeyEvent.ACTION_DOWN) {
                if (!modifier && vncExtraKeys.hasNonStickyModifiers())
                    vncExtraKeys.applyModifiers(true);
                if (!modifier) sendMetaState(metaState, true);
                vncClient.sendKey(keysym, true);
            } else if (action == KeyEvent.ACTION_UP) {
                vncClient.sendKey(keysym, false);
                if (!modifier) sendMetaState(metaState, false);
                if (!modifier && vncExtraKeys.hasNonStickyModifiers())
                    vncExtraKeys.applyModifiers(false);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static int shiftedSymbolToBase(int keysym) {
        switch (keysym) {
            case XK_exclam:
                return XK_1;            // '!' -> Shift+1
            case XK_at:
                return XK_2;            // '@' -> Shift+2
            case XK_numbersign:
                return XK_3;            // '#' -> Shift+3
            case XK_dollar:
                return XK_4;            // '$' -> Shift+4
            case XK_percent:
                return XK_5;            // '%' -> Shift+5
            case XK_asciicircum:
                return XK_6;            // '^' -> Shift+6
            case XK_ampersand:
                return XK_7;            // '&' -> Shift+7
            case XK_asterisk:
                return XK_8;            // '*' -> Shift+8
            case XK_parenleft:
                return XK_9;            // '(' -> Shift+9
            case XK_parenright:
                return XK_0;            // ')' -> Shift+0
            case XK_underscore:
                return XK_minus;        // '_' -> Shift+-
            case XK_plus:
                return XK_equal;        // '+' -> Shift+=
            case XK_asciitilde:
                return XK_grave;        // '~' -> Shift+`
            case XK_braceleft:
                return XK_bracketleft;  // '{' -> Shift+[
            case XK_braceright:
                return XK_bracketright; // '}' -> Shift+]
            case XK_bar:
                return XK_backslash;    // '|' -> Shift+\
            case XK_colon:
                return XK_semicolon;    // ':' -> Shift+;
            case XK_quotedbl:
                return XK_apostrophe;   // '"' -> Shift+'
            case XK_less:
                return XK_comma;        // '<' -> Shift+,
            case XK_greater:
                return XK_period;       // '>' -> Shift+.
            case XK_question:
                return XK_slash;        // '?' -> Shift+/
            default:
                return 0;
        }
    }

    private void sendMetaState(int metaState, boolean down) {
        if ((metaState & META_SHIFT_ON) != 0)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_SHIFT_LEFT), down);
        if ((metaState & META_CTRL_ON) != 0)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_CTRL_LEFT), down);
        if ((metaState & META_ALT_ON) != 0)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_ALT_LEFT), down);
        if ((metaState & META_META_ON) != 0)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_META_LEFT), down);
    }

    protected static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case KEYCODE_SHIFT_LEFT:
            case KEYCODE_SHIFT_RIGHT:
            case KEYCODE_CTRL_LEFT:
            case KEYCODE_CTRL_RIGHT:
            case KEYCODE_ALT_LEFT:
            case KEYCODE_ALT_RIGHT:
            case KEYCODE_META_LEFT:
            case KEYCODE_META_RIGHT:
            case KEYCODE_CAPS_LOCK:
                return true;
            default:
                return false;
        }
    }

    protected void toggleSoftKeyboard() {
        var imm = getSystemService(InputMethodManager.class);
        if (imm != null && ivDisplay != null) {
            ivDisplay.requestFocus();
            imm.showSoftInput(ivDisplay, SHOW_IMPLICIT);
        }
    }

    protected VncDisplayView.TextCommitListener createTextCommitListener() {
        return new VncDisplayView.TextCommitListener() {
            @Override
            public void onCommitText(@NonNull CharSequence text) {
                if (vncClient == null || !vncClient.isConnected()) return;
                vncExtraKeys.applyModifiers(true);
                for (int i = 0; i < text.length(); i++) {
                    int ch = text.charAt(i);
                    vncClient.sendKey(ch, true);
                    vncClient.sendKey(ch, false);
                }
                vncExtraKeys.applyModifiers(false);
            }

            @Override
            public void onDeleteSurrounding(int beforeLength, int afterLength) {
                if (vncClient == null || !vncClient.isConnected()) return;
                vncExtraKeys.applyModifiers(true);
                int keysym = androidKeyToXKeysym(KEYCODE_DEL);
                for (int i = 0; i < beforeLength; i++) {
                    vncClient.sendKey(keysym, true);
                    vncClient.sendKey(keysym, false);
                }
                int fwdKeysym = androidKeyToXKeysym(KEYCODE_FORWARD_DEL);
                for (int i = 0; i < afterLength; i++) {
                    vncClient.sendKey(fwdKeysym, true);
                    vncClient.sendKey(fwdKeysym, false);
                }
                vncExtraKeys.applyModifiers(false);
            }
        };
    }

    @SuppressLint("SourceLockedOrientationActivity")
    protected void rotateScreen() {
        int current = getRequestedOrientation();
        switch (current) {
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                break;
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
                break;
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
        }
    }

    @NonNull
    protected String generateVncUri(boolean local) {
        var sb = new StringBuilder();
        var host = resolveAddress(vncHost, local);
        var port = vncPort;
        sb.append(fmt("vnc://%s:%d/", host, port));
        var ps = new StringBuilder();
        if (vncPassword != null && !vncPassword.isEmpty())
            ps.append("VncPassword=").append(encode(vncPassword));
        if (ps.length() > 0)
            sb.append("?").append(ps);
        return sb.toString();
    }

    protected void openWithExternalApp() {
        var url = generateVncUri(false);
        var host = resolveAddress(vncHost, false);
        var target = fmt("%s:%d", host, vncPort);
        boolean hasPassword = vncPassword != null && !vncPassword.isEmpty();
        var view = getLayoutInflater().inflate(R.layout.dialog_vnc_external, null);
        TextView etTarget = view.findViewById(R.id.et_target);
        TextView etPassword = view.findViewById(R.id.et_password);
        TextInputLayout tilPassword = view.findViewById(R.id.til_password);
        etTarget.setText(target);
        if (hasPassword) {
            etPassword.setText(vncPassword);
        } else {
            tilPassword.setVisibility(GONE);
        }
        DialogInterface.OnClickListener onConnect = (d, w) -> {
            var intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.vnc_menu_no_vnc_app, Toast.LENGTH_SHORT).show();
            }
        };
        var builder = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vnc_external_title)
            .setView(view)
            .setNegativeButton(R.string.vnc_external_cancel, null)
            .setPositiveButton(R.string.vnc_external_connect, onConnect);
        if (hasPassword)
            builder.setNeutralButton(R.string.vnc_external_copy_password, null);
        var dialog = builder.show();
        if (hasPassword) dialog.getButton(BUTTON_NEUTRAL).setOnClickListener(v -> {
            var cm = getSystemService(ClipboardManager.class);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("VNC Password", vncPassword));
            Toast.makeText(this, R.string.vnc_menu_url_copied, Toast.LENGTH_SHORT).show();
        });
    }

    protected void showVncUrl() {
        var local = generateVncUri(true);
        var remote = generateVncUri(false);
        boolean sameUrl = local.equals(remote);
        boolean hasPassword = vncPassword != null && !vncPassword.isEmpty();
        var view = getLayoutInflater().inflate(R.layout.dialog_vnc_url, null);
        TextView etLocal = view.findViewById(R.id.et_local);
        TextView etRemote = view.findViewById(R.id.et_remote);
        TextView etPassword = view.findViewById(R.id.et_password);
        TextInputLayout tilPassword = view.findViewById(R.id.til_password);
        TextInputLayout tilRemote = view.findViewById(R.id.til_remote);
        etLocal.setText(local);
        if (sameUrl) {
            tilRemote.setVisibility(GONE);
        } else {
            etRemote.setText(remote);
        }
        if (hasPassword) {
            etPassword.setText(vncPassword);
        } else {
            tilPassword.setVisibility(GONE);
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vnc_menu_view_url)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    protected void reconnect() {
        if (executor.isShutdown()) {
            Log.w(TAG, "Executor already shut down, skipping reconnect");
            return;
        }
        running = false;
        reconnectAttempt = 0;
        if (vncClient != null) vncClient.requestStop();
        onClearDisplay();
        synchronized (bitmapLock) {
            if (displayBitmap != null) {
                displayBitmap.recycle();
                displayBitmap = null;
            }
        }
        fbWidth = 0;
        fbHeight = 0;
        setStatus(getString(R.string.vnc_display_connecting), VncStatus.CONNECTING);
        showConnectingOverlay(getString(R.string.vnc_display_connecting));
        executor.submit(() -> {
            if (vncClient != null) {
                vncClient.disconnect();
                vncClient = null;
            }
            mainHandler.post(this::fetchVncInfoAndConnect);
        });
    }

    protected boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_keyboard) {
            toggleSoftKeyboard();
            return true;
        } else if (id == R.id.menu_rotate) {
            rotateScreen();
            return true;
        } else if (id == R.id.menu_reconnect) {
            reconnect();
            return true;
        } else if (id == R.id.menu_external) {
            openWithExternalApp();
            return true;
        } else if (id == R.id.menu_view_url) {
            showVncUrl();
            return true;
        }
        return false;
    }
}
