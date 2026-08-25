// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.base;

import static android.content.DialogInterface.BUTTON_NEUTRAL;
import static android.graphics.drawable.GradientDrawable.OVAL;
import static android.view.KeyEvent.*;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
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
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.ExecutorService;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.perf.GamePerfHint;
import cn.classfun.droidvm.lib.ui.ImeInsetsExempt;
import cn.classfun.droidvm.lib.store.vm.DisplayTransportCap;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264ConsolePipeline;
import cn.classfun.droidvm.ui.vm.display.vnc.input.VncExtraKeysPanel;

public abstract class BaseVncActivity extends AppCompatActivity implements ImeInsetsExempt {
    protected final String TAG = getClass().getSimpleName();
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_VM_ID = "vm_id";
    /**
     * Which screen's VNC server to connect to. A VM can run one per screen on different ports,
     * so the daemon is asked for that screen's settings rather than for "the VM's VNC".
     */
    public static final String EXTRA_SCREEN = "screen";
    /**
     * Whether that screen was configured with its own absolute input devices. Used only to say
     * why an input mode is doing nothing; where the events go is the daemon's answer, not this
     * one. Modes that ride RFB (the tablet pointer, the keyboard) are unaffected either way.
     */
    public static final String EXTRA_INPUT_ENABLED = "input_enabled";
    protected static final int DEFAULT_PORT = 5900;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 2000;
    /**
     * How long the message loop parks for while RFB updates are suppressed. The same order as the
     * loop's own socket wait, so resuming costs no more than an ordinary iteration.
     */
    private static final long SUPPRESSED_POLL_MS = 50;
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
    /** Screen whose VNC server this view shows; empty means "the VM's first bound one". */
    protected String screenId = "";
    /** Whether that screen has absolute input devices at all; see {@link #EXTRA_INPUT_ENABLED}. */
    protected boolean screenInputEnabled = true;
    /**
     * Where this screen's H.264 side channel would be, or -1 when the binding does not permit one.
     * Answered by the daemon, which owns both the ceiling and the port the VM was started with.
     */
    protected int h264Port = -1;
    protected String vncHost = "127.0.0.1";
    // Phone LAN address the daemon resolved for an IPv4-wildcard bind (offload
    // proxy IPs already excluded); empty when not applicable. Preferred over
    // local interface enumeration for the external/remote connect address.
    protected String vncRemoteHost = "";
    protected int vncPort = DEFAULT_PORT;
    protected String vncPassword = null;
    protected volatile boolean running = false;
    protected volatile boolean needsRefresh = false;
    /**
     * Whether the message loop is holding off on RFB framebuffer updates because a decoder is
     * painting the screen instead. See {@link #setRfbUpdatesSuppressed}.
     */
    private volatile boolean rfbUpdatesSuppressed = false;
    /** The decoder view, when this activity's layout has one; null leaves the console RFB-only. */
    protected TextureView h264View;
    private H264ConsolePipeline h264;
    /** What {@link #setStatus} last put in the status line, and the note appended to it. */
    private String statusText = "";
    private String statusNote = "";
    private int reconnectAttempt = 0;
    protected int fbWidth, fbHeight;
    protected Bitmap displayBitmap;
    protected final Object bitmapLock = new Object();
    protected VncStatus status = VncStatus.CONNECTING;
    protected boolean capsLockOn = false;
    protected boolean numLockOn = false;
    protected LedStateListener ledStateListener;

    public interface LedStateListener {
        void onLedStateChanged(boolean caps, boolean num);
    }

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
        screenId = intent.getStringExtra(EXTRA_SCREEN);
        if (screenId == null) screenId = "";
        screenInputEnabled = intent.getBooleanExtra(EXTRA_INPUT_ENABLED, true);
        bindViews();
        setupToolbar();
        // Before onSetupActivity() so subclasses can wire views (e.g. the physical keyboard)
        // to the adapter during their setup.
        vncExtraKeys = new VncExtraKeysPanel(extraKeysPanel);
        onSetupActivity();
        fetchVncInfoAndConnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A VM display is on screen and rendering: tell the platform this is sustained heavy
        // gameplay so its power policy raises clocks (see GamePerfHint).
        GamePerfHint.enterGameplay(this);
        // Going to the background detaches the window and takes the decoder's surface with it, so
        // the H.264 path tears itself down and the console falls back to RFB while it is away.
        // Coming back is therefore the other moment worth probing: without this the upgrade would
        // be lost for the rest of the session by the ordinary act of switching apps.
        if (h264 != null && !h264.isRunning() && h264Port > 0 && fbWidth > 0)
            h264.start(vncHost, h264Port);
    }

    @Override
    protected void onPause() {
        super.onPause();
        GamePerfHint.exitGameplay(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onDestroyExtra();
        // First: it holds a socket and a codec, and the loop below waits for a thread that the
        // suppression flag could otherwise leave parked.
        stopH264();
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
        // Null for a layout that has no decoder view, which is how a console opts out of the H.264
        // path entirely: nothing else in here has to be told about it.
        h264View = findViewById(R.id.texture_h264);
        if (h264View != null)
            h264 = new H264ConsolePipeline(h264View, mainHandler, new H264Listener());
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
            // Adopt the screen the daemon resolved. Asking for "the VM's VNC" is answered with a
            // particular screen's server, and input has to agree with that answer: the absolute
            // devices are per screen, so a console still holding "" would have nowhere to send a
            // touch even though it is showing a screen that has one.
            var resolved = resp.optString("screen", "");
            if (!resolved.isEmpty()) screenId = resolved;
            vncHost = resp.optString("host", "127.0.0.1");
            vncRemoteHost = resp.optString("remote_host", "");
            vncPort = resp.optInt("port", DEFAULT_PORT);
            // The pre-gate on the H.264 side channel: a binding whose ceiling stops below the
            // encoder has no side channel to find, and probing for one would spend a connect
            // timeout learning what the config already says. It is only a pre-gate -- the ceiling
            // permits an encoder rather than promising one -- so what settles it is the connect.
            var cap = DisplayTransportCap.fromToken(resp.optString("transport_cap", ""));
            h264Port = cap == DisplayTransportCap.GPU_HW ? resp.optInt("h264_port", -1) : -1;
            vncPassword = resp.optString("password", "");
            if (vncPassword.isEmpty()) vncPassword = null;
            mainHandler.post(this::startVnc);
        };
        DaemonConnection.getInstance().buildRequest("vm_vnc_info")
            .put("vm_id", vmId)
            .put("screen", screenId)
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
                // Also the resize path, and deliberately: the side channel announces its geometry
                // once, in the header, so a guest that changed resolution is a stream describing
                // the old one. Reconnecting is what gets a new header -- and a sync frame with it.
                restartH264();
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
            if (rfbUpdatesSuppressed) {
                // Not calling processMessages IS the suppression: libvncclient asks for the next
                // incremental update from inside HandleRFBServerMessage, at the end of the one it
                // just handled, so a loop that stops handling messages stops asking. The server
                // answers requests and nothing else, so it goes quiet after the one already
                // outstanding -- and the connection stays up the whole time, which is what keeps
                // the keyboard and the tablet pointer working (both are writes, and writes do not
                // go through here).
                //
                // What is given up is noticing a dead RFB server while this lasts, since that is
                // only found out by reading. It costs nothing in practice: the two servers are the
                // same process, so whatever killed one closes the side channel too, and that is
                // what lifts this flag.
                try {
                    Thread.sleep(SUPPRESSED_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
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
        // The side channel belongs to the RFB session that just ended: its port comes from a
        // vm_vnc_info answer this is about to ask for again, and the server it was connected to is
        // gone with the VM. It is opened again by the reconnected session's first framebuffer.
        stopH264();
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

    /**
     * Starts (or restarts) the H.264 side channel for this screen, if the binding offers one.
     *
     * <p>Always a fresh connection rather than a reconfiguration: the stream's geometry is in its
     * header and the server produces a sync frame for whoever just arrived, so reconnecting is both
     * how the console learns the new size and how it gets a picture it can decode from scratch.</p>
     */
    private void restartH264() {
        if (h264 == null || isFinishing() || isDestroyed()) return;
        h264.stop();
        // Not gated on `running`: this runs off the framebuffer callback, which libvncclient makes
        // while it is still inside rfbInitClient -- so the flag the message loop sets afterwards
        // may not be up yet, and gating on it would skip the probe for exactly the first
        // framebuffer, the one every connection has.
        if (h264Port > 0) h264.start(vncHost, h264Port);
    }

    /** Takes the H.264 path down and makes sure RFB is drawing again. */
    private void stopH264() {
        if (h264 != null) h264.stop();
        setRfbUpdatesSuppressed(false);
    }

    /**
     * Stops or resumes asking the VNC server for framebuffer updates, without touching the
     * connection.
     *
     * <p>Suppression is worth having because the two pictures are the same picture: while the
     * decoder is up, every RFB rectangle the server encodes is work spent on a canvas nobody can
     * see. What makes it safe to resume is that libvncserver accumulates each client's modified
     * region whether or not that client is asking, so the request sent on the way back is answered
     * with everything that changed in the meantime -- the fallback converges in one round trip
     * rather than showing whatever was on screen when the decoder took over.</p>
     *
     * <p>Repainting from the framebuffer immediately on resume is the other half: the bytes for the
     * last update the client did receive are still in it, so the canvas has something correct to
     * show during that round trip instead of the black it was left at.</p>
     */
    protected void setRfbUpdatesSuppressed(boolean suppressed) {
        if (rfbUpdatesSuppressed == suppressed) return;
        rfbUpdatesSuppressed = suppressed;
        Log.i(TAG, fmt("RFB framebuffer updates %s",
            suppressed ? "suppressed for the H.264 stream" : "resumed"));
        if (!suppressed) needsRefresh = true;
    }

    /** Whether the H.264 decoder is what is currently painting this console. */
    protected boolean isH264Live() {
        return h264 != null && h264.isLive();
    }

    /** The console's own view of the H.264 path, on the main thread. */
    private final class H264Listener implements H264ConsolePipeline.Listener {
        @Override
        public void onStreamLive(int width, int height) {
            setRfbUpdatesSuppressed(true);
            setStatusNote(getString(R.string.vnc_display_h264_active));
            onH264StreamChanged(true);
        }

        @Override
        public void onStreamGone(boolean wasLive, @Nullable Exception cause) {
            setRfbUpdatesSuppressed(false);
            // Only a stream that was actually on screen has a fallback to report. A probe that
            // found nothing listening is the ordinary case for a VM whose host has no encoder
            // standing behind the port, and saying "fell back to RFB" for it would describe a
            // downgrade that never happened.
            setStatusNote(wasLive ? getString(R.string.vnc_display_h264_fallback) : null);
            onH264StreamChanged(false);
        }
    }

    /**
     * Hook for subclasses that have to move something when the decoder view appears or goes away.
     * The default console has nothing to do here -- the two views share one geometry.
     */
    @SuppressWarnings("unused")
    protected void onH264StreamChanged(boolean live) {
    }

    /**
     * Appends a note to the status line, or clears it. Kept beside the status text rather than
     * replacing it: which transport is carrying the picture is a second fact about the same
     * connection, and losing "connected 1280x720" to say it would be a worse trade.
     */
    protected void setStatusNote(@Nullable String note) {
        statusNote = note == null ? "" : note;
        applyStatusText();
    }

    private void applyStatusText() {
        tvStatus.setText(statusNote.isEmpty()
            ? statusText : fmt("%s  ·  %s", statusText, statusNote));
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
        statusText = text;
        applyStatusText();
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
        // A hardware-mouse right-click the framework (or OEM ROM) failed to see consumed gets
        // synthesized as a mouse-sourced BACK key. Inside the VM display that must never navigate
        // back - the right-click itself is delivered to the guest by the pointer handlers.
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK
            && (event.getSource() & android.view.InputDevice.SOURCE_MOUSE) != 0)
            return true;
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
                if (keyCode == KEYCODE_CAPS_LOCK) {
                    capsLockOn = !capsLockOn;
                    notifyLedState();
                } else if (keyCode == KEYCODE_NUM_LOCK) {
                    numLockOn = !numLockOn;
                    notifyLedState();
                }
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

    private void notifyLedState() {
        if (ledStateListener != null)
            ledStateListener.onLedStateChanged(capsLockOn, numLockOn);
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
        if (imm == null || ivDisplay == null) return;
        // Post so the fab-menu popup has finished tearing down: called inline right after the item
        // click, the popup still owns the focus transition and showSoftInput lands before ivDisplay
        // is the served view and does nothing. (The letterbox onClick path already has focus, but
        // routing both through the same retry keeps them consistent.)
        mainHandler.post(() -> tryShowKeyboard(imm, 15));
    }

    // showSoftInput() can return true for a view the IMM isn't serving yet and show nothing, so the
    // success test is imm.isActive(view), retried on a short delay until the input connection is
    // live. The last few rounds force the IME (some ROMs ignore the implicit request).
    private void tryShowKeyboard(@NonNull InputMethodManager imm, int attemptsLeft) {
        if (attemptsLeft <= 0 || isFinishing() || ivDisplay == null) return;
        ivDisplay.requestFocusFromTouch();
        ivDisplay.requestFocus();
        int flag = attemptsLeft <= 3
            ? InputMethodManager.SHOW_FORCED : InputMethodManager.SHOW_IMPLICIT;
        imm.showSoftInput(ivDisplay, flag);
        if (ivDisplay.isFocused() && imm.isActive(ivDisplay)) return;
        mainHandler.postDelayed(() -> tryShowKeyboard(imm, attemptsLeft - 1), 60);
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

    /**
     * Resolves the host for a VNC URI. For the external/remote address, prefers
     * the daemon-resolved phone LAN address ({@link #vncRemoteHost}) when the VM
     * binds the IPv4 wildcard, since it excludes pbridge offload-proxy IPs that
     * local interface enumeration cannot tell apart. Falls back to
     * {@link cn.classfun.droidvm.lib.utils.NetUtils#resolveAddress} otherwise.
     */
    @NonNull
    private String resolveVncHost(boolean local) {
        if (!local && "0.0.0.0".equals(vncHost) && !vncRemoteHost.isEmpty())
            return vncRemoteHost;
        return resolveAddress(vncHost, local);
    }

    @NonNull
    protected String generateVncUri(boolean local) {
        var sb = new StringBuilder();
        var host = resolveVncHost(local);
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
        var host = resolveVncHost(false);
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
        stopH264();
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
        if (id == R.id.menu_rotate) {
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
