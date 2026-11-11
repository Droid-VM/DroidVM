// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.base;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264ConsolePipeline;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264ProbePolicy;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264RectProtocol;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264SyncFrameCache;

/**
 * One VNC connection to a VM screen and what comes over it: the framebuffer, the H.264 stream,
 * and the reconnecting when either goes away. Apart from any activity, so that one connection
 * serves the console and, while the picture is on an external display, the window there too: the
 * screen's sessions are shared ({@link #acquire}), the last {@link #release} closing it.
 *
 * <p>Whoever shows the picture has the decoder view ({@link #setH264View}); every listener hears
 * the framebuffer and the status. The console's input goes through {@link #client()}.</p>
 *
 * <p>Moved out of BaseVncActivity, whose comments it keeps: the H.264 rules are
 * {@code H264_SINGLE_PORT.md}'s.</p>
 */
public final class VncSession {
    private static final String TAG = "VncSession";
    public static final int DEFAULT_PORT = 5900;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 2000;
    /** The policy's clocks are seconds; one read a second is the only timer the H.264 path needs. */
    private static final long H264_TICK_MS = 1000;

    /** What a session tells the console and the external window, on the main thread. */
    public interface Listener {
        /** The status line, and whether the connection is there. */
        void onStatus(@NonNull String text, @NonNull VncStatus status);

        /** A note appended to the status line (which transport paints), or null to clear it. */
        default void onStatusNote(@Nullable String note) {
        }

        /** A new client, after a (re)connect: the one input goes to from now on. */
        default void onClientCreated(@NonNull VncClient client) {
        }

        /** The daemon named the screen the session shows. */
        default void onScreenResolved(@NonNull String screenId) {
        }

        default void onFramebufferReady(int width, int height) {
        }

        default void onBitmapUpdated(@NonNull Bitmap bitmap) {
        }

        /** The framebuffer went away (a resize, a reconnect): drop what was drawn of it. */
        default void onClearDisplay() {
        }

        default void onH264StreamChanged(boolean live, int width, int height) {
        }
    }

    private static final Map<String, VncSession> sessions = new HashMap<>();

    private final Context context;
    private final String key;
    private final String vmId;
    private String screenId;
    private int users;
    private boolean started;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = newSingleThreadExecutor(r -> {
        var t = new Thread(r, fmt("%s-loop", TAG));
        t.setDaemon(true);
        return t;
    });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile VncClient vncClient;
    private String vncHost = "127.0.0.1";
    private String vncRemoteHost = "";
    private int vncPort = DEFAULT_PORT;
    private String vncPassword;
    private volatile boolean running;
    private volatile boolean needsRefresh;
    private volatile boolean stopped;
    private TextureView h264View;
    /** Read on the message-loop thread, written on the main one; see {@link #setH264View}. */
    @Nullable
    private volatile H264ConsolePipeline h264;
    private final H264ProbePolicy h264Probe = new H264ProbePolicy();
    /**
     * The rect a decoder can start on, held for the connection rather than for the pipeline: a
     * pipeline is built when there is a view to draw into, which moves with the picture, while
     * the stream -- and the one reset-flagged rect carrying its parameter sets -- rides a
     * connection that does not stop for any of it.
     */
    private final H264SyncFrameCache syncFrames = new H264SyncFrameCache();
    private final Runnable h264Tick = this::tickH264;
    private int reconnectAttempt;
    private int fbWidth;
    private int fbHeight;
    private Bitmap displayBitmap;
    private final Object bitmapLock = new Object();
    private String statusText = "";
    private VncStatus status = VncStatus.CONNECTING;
    @Nullable
    private String statusNote;

    private VncSession(@NonNull Context context, @NonNull String key, @NonNull String vmId,
                       @NonNull String screenId) {
        this.context = context.getApplicationContext();
        this.key = key;
        this.vmId = vmId;
        this.screenId = screenId;
    }

    /**
     * The session of [vmId]'s [screenId] ("" for the VM's first VNC screen), shared with whoever
     * else shows it; connecting the first time. Release it when done. Main thread.
     */
    @MainThread
    @NonNull
    public static VncSession acquire(@NonNull Context context, @NonNull String vmId,
                                     @NonNull String screenId, @NonNull Listener listener) {
        String key = vmId + "/" + screenId;
        var session = sessions.get(key);
        if (session == null || session.stopped) {
            session = new VncSession(context, key, vmId, screenId);
            sessions.put(key, session);
        }
        session.users++;
        session.listeners.add(listener);
        if (!session.started) {
            session.started = true;
            session.fetchVncInfoAndConnect();
        } else {
            session.replay(listener);
        }
        return session;
    }

    /** [listener] is done with the session; the last one closes it. Main thread. */
    @MainThread
    public void release(@NonNull Listener listener) {
        listeners.remove(listener);
        if (--users > 0) return;
        if (sessions.get(key) == this) sessions.remove(key);
        stop();
    }

    /** What a listener that joined late has missed: the status, the picture's size and pixels. */
    private void replay(@NonNull Listener listener) {
        listener.onStatus(statusText, status);
        if (statusNote != null) listener.onStatusNote(statusNote);
        var client = vncClient;
        if (client != null) listener.onClientCreated(client);
        if (!screenId.isEmpty()) listener.onScreenResolved(screenId);
        if (fbWidth > 0 && fbHeight > 0) {
            listener.onFramebufferReady(fbWidth, fbHeight);
            synchronized (bitmapLock) {
                if (displayBitmap != null && !displayBitmap.isRecycled())
                    listener.onBitmapUpdated(displayBitmap);
            }
        }
        var pipeline = h264;
        if (pipeline != null && pipeline.isLive())
            listener.onH264StreamChanged(true, fbWidth, fbHeight);
    }

    @Nullable
    public VncClient client() {
        return vncClient;
    }

    @NonNull
    public String screenId() {
        return screenId;
    }

    public int width() {
        return fbWidth;
    }

    public int height() {
        return fbHeight;
    }

    @NonNull
    public String host() {
        return vncHost;
    }

    @NonNull
    public String remoteHost() {
        return vncRemoteHost;
    }

    public int port() {
        return vncPort;
    }

    @Nullable
    public String password() {
        return vncPassword;
    }

    private void status(@NonNull String text, @NonNull VncStatus newStatus) {
        statusText = text;
        status = newStatus;
        for (var listener : listeners) listener.onStatus(text, newStatus);
    }

    private void statusNote(@Nullable String note) {
        statusNote = note;
        for (var listener : listeners) listener.onStatusNote(note);
    }

    private void fetchVncInfoAndConnect() {
        if (stopped) return;
        status(context.getString(R.string.vnc_display_connecting), VncStatus.CONNECTING);
        if (vmId.isEmpty()) {
            Log.e(TAG, "No VM ID provided");
            status(context.getString(R.string.vnc_display_connect_failed), VncStatus.ERROR);
            return;
        }
        DaemonConnection.getInstance().buildRequest("vm_vnc_info")
            .put("vm_id", vmId)
            .put("screen", screenId)
            .onResponse(resp -> {
                // Adopt the screen the daemon resolved. Asking for "the VM's VNC" is answered
                // with a particular screen's server, and input has to agree with that answer.
                var resolved = resp.optString("screen", "");
                vncHost = resp.optString("host", "127.0.0.1");
                vncRemoteHost = resp.optString("remote_host", "");
                vncPort = resp.optInt("port", DEFAULT_PORT);
                vncPassword = resp.optString("password", "");
                if (vncPassword.isEmpty()) vncPassword = null;
                mainHandler.post(() -> {
                    if (!resolved.isEmpty()) {
                        screenId = resolved;
                        for (var listener : listeners) listener.onScreenResolved(resolved);
                    }
                    startVnc();
                });
            })
            .onUnsuccessful(resp -> {
                var msg = resp.optString("message", "Failed to get VNC info");
                Log.e(TAG, msg);
                mainHandler.post(() -> status(msg, VncStatus.ERROR));
            })
            .onError(e -> {
                Log.e(TAG, "Failed to query VNC info", e);
                mainHandler.post(() -> status(
                    context.getString(R.string.vnc_display_connect_failed), VncStatus.ERROR));
            })
            .invoke();
    }

    private final class NativeCallback implements VncClient.NativeCallback {
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
                    for (var listener : listeners) listener.onClearDisplay();
                    if (!oldBitmap.isRecycled()) oldBitmap.recycle();
                }
                status(context.getString(R.string.vnc_display_connected, width, height),
                    VncStatus.CONNECTED);
                for (var listener : listeners) listener.onFramebufferReady(width, height);
                // Nothing to restart for the decoder: a guest resize reaches it as an encoding-50
                // rect at the new coded size with the reset flags set.
            });
        }

        @Override
        public void onFramebufferUpdated(int x, int y, int w, int h) {
            needsRefresh = true;
        }

        @Override
        public void onH264Rect(@NonNull byte[] rect, int width, int height) {
            // On the message-loop thread; the clocks are told the time by the caller.
            h264Probe.onStreamRect(SystemClock.elapsedRealtime());
            var pipeline = h264;
            if (pipeline != null) {
                pipeline.submitStreamRect(rect, width, height);
                return;
            }
            // No pipeline yet -- no view to draw into, as while the picture is moving to another
            // display. Kept, and the first pipeline built afterwards primes its decoder with it:
            // the bare IDRs that follow carry no parameter sets.
            syncFrames.rememberIfSync(rect, width, height);
        }

        @Override
        public void onDvhRect(@NonNull byte[] payload) {
            var dvh = H264RectProtocol.parseDvhRect(payload);
            // A rect this build cannot read is ignored rather than reported: that is what the
            // version byte is for.
            if (dvh == null) return;
            var now = SystemClock.elapsedRealtime();
            if (dvh.isHeartbeat()) {
                h264Probe.onHeartbeat(now);
                return;
            }
            if (!dvh.isCapabilities()) return;
            Log.i(TAG, fmt("H.264 capabilities rect: value %d", dvh.value));
            h264Probe.onCapsRect(dvh.value, now);
            mainHandler.post(VncSession.this::applyH264Mode);
        }
    }

    private void startVnc() {
        if (executor.isShutdown() || stopped) return;
        status(context.getString(R.string.vnc_display_connecting), VncStatus.CONNECTING);
        executor.submit(() -> {
            VncClient client;
            try {
                client = new VncClient();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create VncClient", e);
                mainHandler.post(this::scheduleAutoReconnect);
                return;
            }
            vncClient = client;
            mainHandler.post(() -> {
                for (var listener : listeners) listener.onClientCreated(client);
            });
            boolean ok = client.connect(vncHost, vncPort, vncPassword, new NativeCallback());
            if (!ok) {
                Log.e(TAG, "VNC connect failed");
                mainHandler.post(this::scheduleAutoReconnect);
                return;
            }
            running = true;
            reconnectAttempt = 0;
            // The capabilities clock starts at the connection: a server that is going to say
            // anything has said it by its answer to the first request.
            h264Probe.onConnected(SystemClock.elapsedRealtime());
            mainHandler.post(this::startH264Ticks);
            mainHandler.post(() -> {
                int w = client.getWidth();
                int h = client.getHeight();
                if (w > 0 && h > 0)
                    status(context.getString(R.string.vnc_display_connected, w, h),
                        VncStatus.CONNECTED);
            });
            messageLoop();
        });
    }

    /**
     * Reads whatever the server has to say, for as long as the session is up. It does not stop
     * reading while a decoder paints: the H.264 stream arrives on this connection, as rects.
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
        // The sync frame belonged to this connection's stream; the next one is sent its own.
        syncFrames.clear();
        Log.i(TAG, "message loop ended");
        if (wasRunning) mainHandler.post(this::scheduleAutoReconnect);
    }

    private void scheduleAutoReconnect() {
        if (executor.isShutdown() || stopped) return;
        // The decoder belongs to the RFB session that just ended -- the stream rode it.
        stopH264();
        reconnectAttempt++;
        if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            status(context.getString(R.string.vnc_display_reconnect_failed,
                MAX_RECONNECT_ATTEMPTS), VncStatus.ERROR);
            return;
        }
        Log.i(TAG, fmt("Scheduling auto-reconnect attempt %d/%d",
            reconnectAttempt, MAX_RECONNECT_ATTEMPTS));
        status(context.getString(R.string.vnc_display_reconnecting,
            reconnectAttempt, MAX_RECONNECT_ATTEMPTS), VncStatus.CONNECTING);
        for (var listener : listeners) listener.onClearDisplay();
        synchronized (bitmapLock) {
            if (displayBitmap != null) {
                displayBitmap.recycle();
                displayBitmap = null;
            }
        }
        fbWidth = 0;
        fbHeight = 0;
        executor.submit(() -> {
            var client = vncClient;
            if (client != null) {
                client.disconnect();
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

    /** Drops the connection and makes a new one; the user's and the H.264 policy's way out. */
    @MainThread
    public void reconnect() {
        if (executor.isShutdown() || stopped) return;
        stopH264();
        // Not running any more, so the loop's ending is not taken for a dropped connection and
        // a second reconnect scheduled on top of this one.
        running = false;
        reconnectAttempt = 0;
        var client = vncClient;
        if (client != null) client.requestStop();
        for (var listener : listeners) listener.onClearDisplay();
        synchronized (bitmapLock) {
            if (displayBitmap != null) {
                displayBitmap.recycle();
                displayBitmap = null;
            }
        }
        fbWidth = 0;
        fbHeight = 0;
        status(context.getString(R.string.vnc_display_connecting), VncStatus.CONNECTING);
        executor.submit(() -> {
            var old = vncClient;
            if (old != null) {
                old.disconnect();
                vncClient = null;
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
                if (displayBitmap != null && !displayBitmap.isRecycled())
                    for (var listener : listeners) listener.onBitmapUpdated(displayBitmap);
            }
        });
    }

    /**
     * Binds the view the decoder draws into, or unbinds it with null: whoever shows the picture
     * has it, the console or the external display's window. A new view is a new pipeline, primed
     * from the sync frame the connection kept.
     */
    @MainThread
    public void setH264View(@Nullable TextureView view) {
        if (h264View == view) return;
        stopH264();
        h264View = view;
        h264 = view == null || stopped ? null
            : new H264ConsolePipeline(view, mainHandler, new H264Listener(), syncFrames);
    }

    /** Unbinds [view] if it is the decoder's; a view that lost it already is left alone. */
    @MainThread
    public void releaseH264View(@Nullable TextureView view) {
        if (view != null && h264View == view) setH264View(null);
    }

    private void stopH264() {
        var pipeline = h264;
        if (pipeline != null) pipeline.stop();
    }

    @MainThread
    private void startH264Ticks() {
        mainHandler.removeCallbacks(h264Tick);
        mainHandler.postDelayed(h264Tick, H264_TICK_MS);
    }

    @MainThread
    private void tickH264() {
        if (stopped) return;
        var order = h264Probe.tick(SystemClock.elapsedRealtime());
        applyH264Mode();
        if (order == H264ProbePolicy.Order.RECONNECT) {
            // The stream is dead and enrolment happens on connecting, so the only way to ask for a
            // new one is a new connection.
            Log.w(TAG, "the H.264 stream went silent; reconnecting");
            reconnect();
            return;
        }
        if (running) mainHandler.postDelayed(h264Tick, H264_TICK_MS);
    }

    @MainThread
    private void applyH264Mode() {
        var pipeline = h264;
        if (h264Probe.mode() != H264ProbePolicy.Mode.DECODING && pipeline != null) {
            if (h264Probe.isPermanent()) pipeline.disable();
            else pipeline.stop();
        }
        // Said only for the host that answered "no encoder", the one case that cannot get better.
        if (h264Probe.saidNoEncoder())
            statusNote(context.getString(R.string.vnc_display_h264_unavailable));
    }

    /** Whether the H.264 decoder is what is painting the picture now. */
    public boolean isH264Live() {
        var pipeline = h264;
        return pipeline != null && pipeline.isLive();
    }

    private final class H264Listener implements H264ConsolePipeline.Listener {
        @Override
        public void onStreamLive(int width, int height) {
            statusNote(context.getString(R.string.vnc_display_h264_active));
            for (var listener : listeners) listener.onH264StreamChanged(true, width, height);
        }

        @Override
        public void onStreamGone(boolean wasLive, @Nullable Exception cause) {
            for (var listener : listeners) listener.onH264StreamChanged(false, 0, 0);
            if (cause == null) {
                // Nothing went wrong: the window went away, the rects still arrive.
                statusNote(null);
                return;
            }
            if (cause instanceof H264ConsolePipeline.NoDecoderException) {
                // A client that asked for encoding 50 is served no pixels, so one that cannot
                // decode has to withdraw the encodings and connect again.
                Log.w(TAG, "this device has no H.264 decoder; falling back to the pixel path");
                h264Probe.onDecoderUnsupported();
                VncClient.setH264Advertised(false);
                var pipeline = h264;
                if (pipeline != null) pipeline.disable();
                statusNote(context.getString(R.string.vnc_display_h264_fallback));
                reconnect();
                return;
            }
            Log.w(TAG, "the H.264 stream ended", cause);
            statusNote(wasLive ? context.getString(R.string.vnc_display_h264_fallback) : null);
        }
    }

    /** Closes the connection; the last release's doing. */
    @MainThread
    private void stop() {
        stopped = true;
        mainHandler.removeCallbacks(h264Tick);
        stopH264();
        h264 = null;
        running = false;
        var client = vncClient;
        if (client != null) client.requestStop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        client = vncClient;
        if (client != null) {
            client.disconnect();
            vncClient = null;
        }
        synchronized (bitmapLock) {
            if (displayBitmap != null) {
                displayBitmap.recycle();
                displayBitmap = null;
            }
        }
    }
}
