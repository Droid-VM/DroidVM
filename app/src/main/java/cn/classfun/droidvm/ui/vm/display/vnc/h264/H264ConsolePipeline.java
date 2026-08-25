// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * The console's H.264 half, from "try the port" to "the picture is coming from a decoder now".
 *
 * <p>Owns the three pieces that have to agree with each other -- the socket, the codec and the view
 * the codec draws into -- and holds them to one rule: <b>this only ever runs on top of a live RFB
 * connection</b>. The RFB client stays up whatever happens here, input keeps riding it, and the
 * failure of anything in this file is a view being hidden again rather than a console that stops
 * working. Which is also why the fallback path is not an error path: it is the same teardown the
 * ordinary close runs, reached with a reason attached.</p>
 *
 * <p>A {@link TextureView} rather than a {@link android.view.SurfaceView}: the decoder's picture has
 * to land exactly where the RFB canvas was, and the canvas is positioned by a layout pass plus a
 * scale and a translation that the viewport controller recomputes on every chrome, IME and rotation
 * change. A TextureView is an ordinary view in that pass, so "exactly where the canvas is" is the
 * same three property assignments applied twice. A SurfaceView is composited outside it, and would
 * have made the alignment a second, separately-wrong geometry calculation. It is not clickable and
 * not focusable, so touches fall through it to the canvas underneath and every input mode keeps
 * working while it is up.</p>
 *
 * <p><b>Every callback names the channel it came from.</b> A reader thread's last act is to report
 * that its stream ended, and that report arrives on the main thread some time after the object it
 * describes was replaced -- which is not a rare interleaving but the ordinary one, because
 * {@code stop(); start();} is how a resize is handled. Without the name, the dying channel's
 * farewell tore down the channel that had replaced it, and the console sat on RFB until something
 * else happened to probe again.</p>
 */
public final class H264ConsolePipeline {
    private static final String TAG = "H264ConsolePipeline";

    /** Called on the main thread. */
    public interface Listener {
        /** The decoder is rendering. RFB framebuffer updates are now redundant. */
        void onStreamLive(int width, int height);

        /**
         * The pipeline is down and the RFB canvas is what shows the screen again.
         *
         * @param wasLive whether it had ever been on screen -- a probe that simply found nothing
         *                listening is not a fallback and has nothing to tell the user about.
         * @param cause   what ended it, or null when it was closed on purpose.
         */
        void onStreamGone(boolean wasLive, @Nullable Exception cause);
    }

    private final TextureView view;
    private final Handler main;
    private final Listener listener;

    /** Written on the main thread; read by reader threads to find out whether they are still it. */
    @Nullable
    private volatile H264SideChannel channel;
    /** Written on the main thread, read by the reader thread; see {@link ChannelListener}. */
    @Nullable
    private volatile H264ConsoleDecoder decoder;
    @Nullable
    private Surface surface;
    private int streamWidth;
    private int streamHeight;
    private boolean live;
    private boolean stopping;

    public H264ConsolePipeline(@NonNull TextureView view, @NonNull Handler main,
                               @NonNull Listener listener) {
        this.view = view;
        this.main = main;
        this.listener = listener;
    }

    public boolean isRunning() {
        return channel != null;
    }

    public boolean isLive() {
        return live;
    }

    /**
     * Opens the side channel at [host]:[port]. Cheap when there is nothing there: one connect with
     * a short timeout, on a thread of its own, and the console carries on showing RFB either way.
     */
    @MainThread
    public void start(@NonNull String host, int port) {
        if (channel != null || port <= 0) return;
        Log.i(TAG, fmt("probing the H.264 side channel at %s:%d", host, port));
        var events = new ChannelListener();
        var opened = new H264SideChannel(host, port, events);
        // Assigned before start(), so no callback can run before the name it will be checked
        // against exists.
        events.owner = opened;
        channel = opened;
        opened.start();
    }

    /** Takes the pipeline down deliberately: no reason, no notice beyond the state change. */
    @MainThread
    public void stop() {
        teardown(channel, null);
    }

    @MainThread
    private void onHeader(@NonNull H264SideChannel from, int width, int height) {
        if (channel != from || stopping) return;
        streamWidth = width;
        streamHeight = height;
        // The listener goes on before the availability check and stays on for the channel's whole
        // life, because the case it exists for is not only "the surface does not exist yet" but
        // also "the surface stopped existing" -- and the second one arrives on a view that was
        // available when the header did. Installing it only on the not-yet branch is what let a
        // backgrounded console keep a socket and a reader thread for a Surface that was gone.
        view.setSurfaceTextureListener(new SurfaceListener(from));
        view.setVisibility(VISIBLE);
        var texture = view.getSurfaceTexture();
        // The reader thread is parked on the decoder-ready latch until there is one, which is what
        // keeps the frames the server has already produced waiting in its socket rather than being
        // read and dropped.
        if (view.isAvailable() && texture != null) attach(from, texture);
    }

    @MainThread
    private void attach(@NonNull H264SideChannel from, @NonNull SurfaceTexture texture) {
        var opened = channel;
        if (opened != from || stopping || decoder != null) return;
        // The decoder writes frames at the size the header announced; the view scales whatever it
        // is given, so the two never have to be made equal.
        texture.setDefaultBufferSize(streamWidth, streamHeight);
        var target = new Surface(texture);
        surface = target;
        var started = new H264ConsoleDecoder(cause -> main.post(() -> teardown(from, cause)));
        if (!started.start(target, streamWidth, streamHeight)) {
            teardown(from, new IOException("no H.264 decoder on this device"));
            return;
        }
        decoder = started;
        live = true;
        opened.decoderReady();
        listener.onStreamLive(streamWidth, streamHeight);
    }

    /**
     * Puts everything back the way it was, once. Idempotent because it is reached from four places
     * -- the console closing, the stream ending, the decoder failing and the surface going away --
     * and two of them can happen at the same moment.
     *
     * <p>[from] is the channel the caller believes it is ending. A teardown for one that is no
     * longer the current channel is a message from a thread that has already been replaced, and
     * doing its bidding would close the connection that replaced it.</p>
     */
    @MainThread
    private void teardown(@Nullable H264SideChannel from, @Nullable Exception cause) {
        if (stopping || channel == null || channel != from) return;
        stopping = true;
        var wasLive = live;
        live = false;
        if (cause != null) Log.w(TAG, "H.264 side channel down", cause);
        channel.close();
        channel = null;
        if (decoder != null) {
            decoder.release();
            decoder = null;
        }
        view.setSurfaceTextureListener(null);
        view.setVisibility(GONE);
        if (surface != null) {
            surface.release();
            surface = null;
        }
        stopping = false;
        listener.onStreamGone(wasLive, cause);
    }

    /** The decoder's window, watched for the whole life of the channel that asked for it. */
    private final class SurfaceListener implements TextureView.SurfaceTextureListener {
        private final H264SideChannel from;

        SurfaceListener(@NonNull H264SideChannel from) {
            this.from = from;
        }

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
            attach(from, st);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
            // The view moved or resized. The decoder writes at the stream's size and the view
            // scales it, so there is nothing to reconfigure.
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
            // The window went away underneath the decoder; there is nothing to draw into. This is
            // also what backgrounding the console looks like, which is why it has to end the
            // channel rather than merely stop drawing: the socket is the server's one client slot.
            teardown(from, new IOException("the decoder's surface was destroyed"));
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
        }
    }

    /** Everything here arrives on the side channel's reader thread. */
    private final class ChannelListener implements H264SideChannel.Listener {
        /** The channel these callbacks are about; set before that channel is started. */
        H264SideChannel owner;

        @Override
        public void onStreamStarted(int width, int height) {
            main.post(() -> onHeader(owner, width, height));
        }

        @Override
        public void onFrame(@NonNull byte[] annexB) throws IOException {
            // Read without the main thread's help: the decoder was published before the latch this
            // thread was waiting on was counted down, so it is visible here and cannot be null.
            // The name is checked first all the same -- the decoder this would feed belongs to
            // whichever channel is current, and a replaced reader must not be the one filling it.
            if (owner != channel) throw new IOException("this side channel has been replaced");
            var target = decoder;
            if (target == null) throw new IOException("no decoder to feed");
            target.submit(annexB);
        }

        @Override
        public void onStreamEnded(@Nullable Exception cause) {
            main.post(() -> teardown(owner, cause));
        }
    }
}
