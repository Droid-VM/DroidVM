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

    @Nullable
    private H264SideChannel channel;
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
        var opened = new H264SideChannel(host, port, new ChannelListener());
        channel = opened;
        opened.start();
    }

    /** Takes the pipeline down deliberately: no reason, no notice beyond the state change. */
    @MainThread
    public void stop() {
        teardown(null);
    }

    @MainThread
    private void onHeader(int width, int height) {
        if (channel == null || stopping) return;
        streamWidth = width;
        streamHeight = height;
        view.setVisibility(VISIBLE);
        var texture = view.getSurfaceTexture();
        if (view.isAvailable() && texture != null) {
            attach(texture);
            return;
        }
        // Made visible just now, so its SurfaceTexture does not exist yet. The reader thread is
        // parked on the decoder-ready latch until it does, which is what keeps the frames the
        // server has already produced waiting in its socket rather than being read and dropped.
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                attach(st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
                // The view moved or resized. The decoder writes at the stream's size and the view
                // scales it, so there is nothing to reconfigure.
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                // The window went away underneath the decoder; there is nothing to draw into.
                teardown(new IOException("the decoder's surface was destroyed"));
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
            }
        });
    }

    @MainThread
    private void attach(@NonNull SurfaceTexture texture) {
        var opened = channel;
        if (opened == null || stopping || decoder != null) return;
        // The decoder writes frames at the size the header announced; the view scales whatever it
        // is given, so the two never have to be made equal.
        texture.setDefaultBufferSize(streamWidth, streamHeight);
        var target = new Surface(texture);
        surface = target;
        var started = new H264ConsoleDecoder(cause -> main.post(() -> teardown(cause)));
        if (!started.start(target, streamWidth, streamHeight)) {
            teardown(new IOException("no H.264 decoder on this device"));
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
     */
    @MainThread
    private void teardown(@Nullable Exception cause) {
        if (stopping || channel == null) return;
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

    /** Everything here arrives on the side channel's reader thread. */
    private final class ChannelListener implements H264SideChannel.Listener {
        @Override
        public void onStreamStarted(int width, int height) {
            main.post(() -> onHeader(width, height));
        }

        @Override
        public void onFrame(@NonNull byte[] annexB) throws IOException {
            // Read without the main thread's help: the decoder was published before the latch this
            // thread was waiting on was counted down, so it is visible here and cannot be null.
            var target = decoder;
            if (target == null) throw new IOException("no decoder to feed");
            target.submit(annexB);
        }

        @Override
        public void onStreamEnded(@Nullable Exception cause) {
            main.post(() -> teardown(cause));
        }
    }
}
