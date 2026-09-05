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
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MainThread;
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
import cn.classfun.droidvm.lib.ui.CopyableField;
import cn.classfun.droidvm.lib.ui.ImeInsetsExempt;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264ConsolePipeline;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264ProbePolicy;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264RectProtocol;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264SyncFrameCache;
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
     * How often the H.264 policy's two silence clocks are read.
     *
     * <p>Both of them are measured in seconds, so a second of granularity costs nothing and this is
     * the only timer the console needs for the whole H.264 path: everything else about it is driven
     * by rects arriving.</p>
     */
    private static final long H264_TICK_MS = 1000;
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
     * The decoder view, when this console has one; null leaves it RFB-only.
     *
     * <p>Null does not stop the stream arriving: the encodings are asked for by the RFB client
     * itself, before any of this exists. What it means is that the rects have nowhere to go, which
     * is only ever true of a presentation console that has not been given a display yet -- and that
     * console shows nothing on the phone either, so there is no picture to freeze.</p>
     */
    protected TextureView h264View;
    /** Read on the message-loop thread, written on the main one; see {@link #setH264View}. */
    @Nullable
    private volatile H264ConsolePipeline h264;
    /** What this console is doing about H.264 and why. See {@link H264ProbePolicy}. */
    private final H264ProbePolicy h264Probe = new H264ProbePolicy();
    /**
     * The rect a decoder can start on, held for the connection rather than for the pipeline.
     *
     * <p>Here rather than inside {@link H264ConsolePipeline} because it has to survive one: this
     * console's pipeline is built when there is a view to draw into, which on the presentation
     * console is after a display has been chosen and again after every window rebuild, while the
     * stream -- and the one reset-flagged rect that carries its parameter sets -- rides a
     * connection that started earlier and does not stop for any of it.</p>
     */
    private final H264SyncFrameCache syncFrames = new H264SyncFrameCache();
    private final Runnable h264Tick = this::tickH264;
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
        // Nothing to restart here any more. Going to the background takes the decoder's surface
        // with the window, and coming back gives it a new one -- which the pipeline hears about
        // from the view itself. The stream never stopped: it rides the RFB connection, which stays
        // up the whole time, so the next rect after the surface returns is the one that draws.
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
        // First: it holds a codec, and the loop below waits for the message-loop thread that may
        // be parked inside a submit to it.
        mainHandler.removeCallbacks(h264Tick);
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
        // Null for a layout that has no decoder view of its own, which is both how a console opts
        // out of the H.264 path entirely and how the presentation defers the question until it
        // knows which display it is putting the picture on.
        setH264View(findViewById(R.id.texture_h264));
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
            // Nothing here about H.264 any more. There is no second port to be told about, and the
            // binding's transport ceiling is not the answer either -- it says what the host is
            // permitted to build, and what the console needs is whether it built one. That is
            // answered on the connection itself, by the capabilities rect.
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
                // Nothing to restart for the decoder: a guest resize reaches it as an
                // encoding-50 rect at the new coded size with the reset flags set, which is a new
                // decoder generation and a sync frame to start it on, arriving in that order
                // because the server sends the DesktopSize rect first.
            });
        }

        @Override
        public void onFramebufferUpdated(int x, int y, int w, int h) {
            needsRefresh = true;
        }

        @Override
        public void onH264Rect(@NonNull byte[] rect, int width, int height) {
            // On the message-loop thread. Both of these are told the time by the caller rather
            // than reading a clock of their own, which is what lets the schedule be tested.
            h264Probe.onStreamRect(SystemClock.elapsedRealtime());
            var pipeline = h264;
            if (pipeline != null) {
                pipeline.submitStreamRect(rect, width, height);
                return;
            }
            // No pipeline yet, which for the presentation console is the ordinary state until a
            // display has been chosen -- the connection does not wait for that choice, and the rect
            // that starts the stream is sent once, on joining. Dropping it outright is what left
            // that console showing nothing: the bare IDRs that follow carry no parameter sets, and
            // nothing on the wire asks for another. Kept here instead, and the first pipeline built
            // afterwards primes its decoder with it.
            syncFrames.rememberIfSync(rect, width, height);
        }

        @Override
        public void onDvhRect(@NonNull byte[] payload) {
            var dvh = H264RectProtocol.parseDvhRect(payload);
            // A rect this build cannot read is ignored rather than reported: that is what the
            // version byte is for, and dropping the connection over a newer host's vocabulary
            // would turn a gap into an outage.
            if (dvh == null) return;
            var now = SystemClock.elapsedRealtime();
            if (dvh.isHeartbeat()) {
                h264Probe.onHeartbeat(now);
                return;
            }
            if (!dvh.isCapabilities()) return;
            Log.i(TAG, fmt("H.264 capabilities rect: value %d", dvh.value));
            h264Probe.onCapsRect(dvh.value, now);
            mainHandler.post(BaseVncActivity.this::applyH264Mode);
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
            // The five-second capabilities clock starts at the connection, not at the first
            // picture: the server answers the client's first request with the capabilities rect,
            // so a server that is going to say anything has said it by then.
            h264Probe.onConnected(SystemClock.elapsedRealtime());
            mainHandler.post(this::startH264Ticks);
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

    /**
     * Reads whatever the server has to say, for as long as this console is up.
     *
     * <p>It no longer stops reading while a decoder is painting the screen, and it cannot: the
     * H.264 stream arrives on this connection, as rects, so a loop that stopped handling messages
     * would stop the picture it was trying to make room for. Suppressing the pixel work is the
     * server's job now -- it empties an enrolled client's modified region rather than encoding it
     * -- and the framebuffer copy on this side stops on its own, because the rect handlers say the
     * framebuffer did not move.</p>
     */
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
        h264Probe.onDisconnected();
        // The sync frame belonged to this connection's stream. The next connection joins the stream
        // again and is sent its own; keeping this one would prime a decoder with the parameter sets
        // of a stream nobody is sending any more. Cleared from this thread because this is the
        // thread that writes it -- the loop above is the only other place it is touched.
        syncFrames.clear();
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
        // The decoder belongs to the RFB session that just ended -- the stream rode it -- so it
        // goes with it. The reconnected session enrols itself and starts a new one.
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
     * Binds the view the decoder draws into, or unbinds it with null.
     *
     * <p>Here rather than only in {@link #bindViews} because one console's decoder surface is not
     * in its own layout: the presentation puts the guest's picture on another display entirely, in
     * a window that does not exist until a display has been chosen and can be dismissed and rebuilt
     * while the console stays open. Rebinding is a whole new pipeline, since the view a pipeline
     * draws into is the one thing about it that cannot change underneath.</p>
     *
     * <p>Binding does not ask for anything. Nothing has to be asked for any more: the encodings go
     * out with the connection, and a pipeline bound halfway through a stream starts decoding at the
     * next rect that reaches it.</p>
     */
    protected void setH264View(@Nullable TextureView view) {
        if (h264View == view) return;
        stopH264();
        h264View = view;
        h264 = view == null ? null
            : new H264ConsolePipeline(view, mainHandler, new H264Listener(), syncFrames);
    }

    /**
     * Takes the H.264 path down.
     *
     * <p>Deliberately does not stop the policy's tick. One of the callers is
     * {@link #setH264View}, which runs while the connection is perfectly alive -- the presentation
     * console rebinds its decoder view when a display is chosen -- and a tick cancelled there would
     * never be posted again, leaving the console with no clock for the rest of its life. The tick
     * stops on its own when the message loop does, because that is the thing it is about.</p>
     */
    private void stopH264() {
        var pipeline = h264;
        if (pipeline != null) pipeline.stop();
    }

    /** Starts the once-a-second read of the policy's two silence clocks. Idempotent. */
    @MainThread
    private void startH264Ticks() {
        mainHandler.removeCallbacks(h264Tick);
        mainHandler.postDelayed(h264Tick, H264_TICK_MS);
    }

    /**
     * One read of the policy's clocks, and whatever it asks for as a result.
     *
     * <p>This is the whole of the timing half of {@code H264_SINGLE_PORT.md} section 1: five
     * seconds of no capabilities rect means the server is not one that knows about them, and ten
     * seconds of neither frame nor heartbeat while decoding means the stream is dead however alive
     * the socket looks. Everything else on the H.264 path is driven by rects arriving.</p>
     */
    @MainThread
    private void tickH264() {
        if (isFinishing() || isDestroyed()) return;
        var order = h264Probe.tick(SystemClock.elapsedRealtime());
        applyH264Mode();
        if (order == H264ProbePolicy.Order.RECONNECT) {
            // The stream is dead and the connection is where enrolment happens, so the only way to
            // ask for a new one is a new connection. Nothing else here reconnects on its own: the
            // message loop only does so when the socket itself failed, and this failure is a
            // socket that is fine and a picture that stopped.
            Log.w(TAG, "the H.264 stream went silent; reconnecting");
            reconnect();
            return;
        }
        if (running) mainHandler.postDelayed(h264Tick, H264_TICK_MS);
    }

    /**
     * Makes the console show what the policy says it should be showing.
     *
     * <p>Only ever takes the decoder down, never puts it up: a pipeline is started by a rect
     * arriving, not by a decision here. What this settles is the two things a decision can settle
     * on its own -- whether a pipeline that is up should stay up, and what the status line says.</p>
     */
    @MainThread
    private void applyH264Mode() {
        var pipeline = h264;
        if (h264Probe.mode() != H264ProbePolicy.Mode.DECODING && pipeline != null) {
            if (h264Probe.isPermanent()) pipeline.disable();
            else pipeline.stop();
        }
        // Said only for the host that answered "no encoder", which is the one case where this
        // console can never do better and the user might otherwise wonder why. Silence is not that
        // case: an ordinary VNC server never offered a stream, and telling its user that H.264 is
        // unavailable would be noise on every screen that was never going to have one.
        if (h264Probe.saidNoEncoder())
            setStatusNote(getString(R.string.vnc_display_h264_unavailable));
    }

    /** Whether the H.264 decoder is what is currently painting this console. */
    protected boolean isH264Live() {
        var pipeline = h264;
        return pipeline != null && pipeline.isLive();
    }

    /** The console's own view of the H.264 path, on the main thread. */
    private final class H264Listener implements H264ConsolePipeline.Listener {
        @Override
        public void onStreamLive(int width, int height) {
            setStatusNote(getString(R.string.vnc_display_h264_active));
            onH264StreamChanged(true, width, height);
        }

        @Override
        public void onStreamGone(boolean wasLive, @Nullable Exception cause) {
            onH264StreamChanged(false, 0, 0);
            if (cause == null) {
                // Nothing went wrong: the console is closing, or its window went away, and the
                // rects are still arriving on a connection that is still up.
                setStatusNote(null);
                return;
            }
            if (cause instanceof H264ConsolePipeline.NoDecoderException) {
                // The one failure the console has to act on rather than merely report. A client
                // that asked for encoding 50 is served no pixels, so a console that cannot decode
                // has to stop asking before it can have a picture at all -- and since the ask is
                // made by the RFB client at connect time, that means withdrawing the encodings and
                // opening a new connection.
                Log.w(TAG, "this device has no H.264 decoder; falling back to the pixel path");
                h264Probe.onDecoderUnsupported();
                VncClient.setH264Advertised(false);
                // Latched here rather than left to the next tick: the connection being torn down
                // can still deliver a rect on its way out, and one that reached a pipeline still
                // willing to try would fail the same way and ask for another reconnect.
                var pipeline = h264;
                if (pipeline != null) pipeline.disable();
                setStatusNote(getString(R.string.vnc_display_h264_fallback));
                reconnect();
                return;
            }
            Log.w(TAG, "the console's H.264 stream ended", cause);
            // A downgrade the user watched happen is the only one worth naming as one. Anything
            // that failed before there was ever a picture is noise about a thing nobody saw.
            setStatusNote(wasLive ? getString(R.string.vnc_display_h264_fallback) : null);
        }
    }

    /**
     * Hook for subclasses that have to move something when the decoder view appears or goes away.
     * The default console has nothing to do here -- the two views share one geometry, written by
     * the same viewport controller. [width] and [height] are the stream's, and zero when it is not
     * live, for the console whose decoder view has to be letterboxed by hand.
     */
    @SuppressWarnings("unused")
    protected void onH264StreamChanged(boolean live, int width, int height) {
    }

    /**
     * Appends a note to the status line, or clears it. Kept beside the status text rather than
     * replacing it: which transport is carrying the picture is a second fact about the same
     * connection, and losing "connected 1280x720" to say it would be a worse trade.
     */
    protected void setStatusNote(@Nullable String note) {
        var next = note == null ? "" : note;
        // A note that has not changed is not written again. The H.264 policy is read once a second
        // and re-states its verdict every time, which without this would be a setText per second
        // for the whole life of a console that has settled on the pixel path.
        if (statusNote.equals(next)) return;
        statusNote = next;
        applyStatusText();
    }

    private void applyStatusText() {
        tvStatus.setText(statusNote.isEmpty()
            ? statusText : fmt("%s  \u00b7  %s", statusText, statusNote));
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

    /**
     * The one connection dialog: what to point a viewer at, and the handoff to one.
     *
     * <p>It shows the {@code vnc://} URI for this device and, when the server is bound wider than
     * loopback, the one another machine on the network would use -- the pair the separate "view
     * URL" dialog used to show, which is why there is no longer a separate dialog: a bare
     * {@code host:port} said nothing the URI does not already say. Connect hands the network URI
     * to whatever app claims {@code vnc://}; the password rides in it as a query parameter, and
     * the copy button is there for the viewers that ignore it.</p>
     */
    protected void openWithExternalApp() {
        var localUrl = generateVncUri(true);
        var remoteUrl = generateVncUri(false);
        boolean sameUrl = localUrl.equals(remoteUrl);
        boolean hasPassword = vncPassword != null && !vncPassword.isEmpty();
        var view = getLayoutInflater().inflate(R.layout.dialog_vnc_external, null);
        EditText etLocal = view.findViewById(R.id.et_local);
        EditText etRemote = view.findViewById(R.id.et_remote);
        TextView etPassword = view.findViewById(R.id.et_password);
        TextInputLayout tilRemote = view.findViewById(R.id.til_remote);
        TextInputLayout tilPassword = view.findViewById(R.id.til_password);
        etLocal.setText(localUrl);
        CopyableField.setupReadOnly(etLocal, getString(R.string.vnc_external_hint_local));
        if (sameUrl) {
            tilRemote.setVisibility(GONE);
        } else {
            etRemote.setText(remoteUrl);
            CopyableField.setupReadOnly(etRemote, getString(R.string.vnc_external_hint_remote));
        }
        if (hasPassword) {
            etPassword.setText(vncPassword);
        } else {
            tilPassword.setVisibility(GONE);
        }
        DialogInterface.OnClickListener onConnect = (d, w) -> {
            var intent = new Intent(Intent.ACTION_VIEW, Uri.parse(remoteUrl));
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
        if (hasPassword) dialog.getButton(BUTTON_NEUTRAL).setOnClickListener(v ->
            CopyableField.copy(this, vncPassword, getString(R.string.vnc_external_hint_password)));
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
        }
        return false;
    }
}
