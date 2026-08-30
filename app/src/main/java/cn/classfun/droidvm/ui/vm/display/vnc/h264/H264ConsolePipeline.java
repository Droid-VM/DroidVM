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

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The console's H.264 half: encoding-50 rects in, a picture on a {@link TextureView} out.
 *
 * <p>There is no socket here any more. The frames arrive on the ordinary RFB connection as rects,
 * so what this owns is the two pieces that still have to agree with each other -- the codec and the
 * view it draws into -- under the same rule as before: <b>this only ever runs on top of a live RFB
 * connection</b>. The RFB client stays up whatever happens here, input keeps riding it, and the
 * failure of anything in this file is a view being hidden again rather than a console that stops
 * working.</p>
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
 * <p><b>Every callback names the generation it came from.</b> A decoder's failure, or its surface
 * going away, arrives on the main thread some time after the object it describes was replaced --
 * which is not a rare interleaving but the ordinary one, because a guest resize replaces the
 * decoder while frames for both sizes are in flight. Without the name, the dying generation's
 * farewell tore down the one that had replaced it.</p>
 *
 * <p><b>A geometry is a generation, and a generation is a decoder.</b> MediaCodec is configured for
 * one size, so a rect at a new one cannot be fed to the codec that was standing; the rect carries
 * the coded size for exactly this reason, and the server sets the reset flags on the first rect at
 * a new geometry so that the bytes behind it are a sync frame rather than a continuation.</p>
 */
public final class H264ConsolePipeline {
    private static final String TAG = "H264ConsolePipeline";
    /**
     * How long a frame waits for the main thread to answer a new geometry with a decoder.
     *
     * <p>Normally microseconds -- the console has been on screen showing RFB, so the view is
     * already available and the answer is one main-thread post away. The wait exists so that the
     * first rect of a stream, which is the sync frame the whole stream is built on, is not the one
     * frame that gets dropped. It is bounded because a wedged main thread must not park the RFB
     * message loop, which is also what carries the keyboard.</p>
     */
    private static final long CONFIGURE_WAIT_MS = 1500;

    /** This device has no {@code video/avc} decoder, so no connection here can ever show one. */
    public static final class NoDecoderException extends IOException {
        NoDecoderException() {
            super("no H.264 decoder on this device");
        }
    }

    /** Called on the main thread. */
    public interface Listener {
        /** The decoder is rendering. The RFB canvas underneath is now redundant. */
        void onStreamLive(int width, int height);

        /**
         * The pipeline is down and the RFB canvas is what shows the screen again.
         *
         * @param wasLive whether it had ever been on screen.
         * @param cause   what ended it, or null when nothing went wrong -- a deliberate close, or
         *                the window going away underneath it, which is what backgrounding the
         *                console looks like and is not a fault to report.
         */
        void onStreamGone(boolean wasLive, @Nullable Exception cause);
    }

    /**
     * One decoder, for one coded geometry, and the latch that says whether it exists yet.
     *
     * <p>The latch is settled rather than signalled: it counts down once the main thread has
     * decided, whether the decision was a decoder or "there is nowhere to draw". A frame arriving
     * while there is nowhere to draw has to be dropped immediately rather than waited on, because
     * the alternative is the message loop stalling for a second and a half per frame for as long as
     * the console is in the background.</p>
     */
    private static final class Generation {
        final int width;
        final int height;
        final CountDownLatch settled = new CountDownLatch(1);
        @Nullable
        volatile H264ConsoleDecoder decoder;

        /**
         * The Annex-B bytes of the most recent reset-flagged rect at this geometry -- SPS, PPS and
         * an IDR concatenated -- or null before one has been seen.
         *
         * <p>This is the whole reason a black screen was possible. The server sends the parameter
         * sets exactly once, on the reset-flagged rect that starts the stream, and never again on
         * the bare IDRs that follow; a decoder attached even one frame later than that rect --
         * which is the ordinary case on a cold open, because the TextureView's SurfaceTexture is
         * created a frame after the view is made visible -- would never see an SPS and would buffer
         * every later frame forever with nothing to decode them against. Held here so that a
         * decoder standing up after the sync rect can still be handed it.</p>
         *
         * <p>Written and read only on the message-loop thread; see {@link #submitStreamRect}.</p>
         */
        @Nullable
        byte[] syncFrame;
        /** Whether this generation's decoder has been fed yet. Message-loop thread only. */
        boolean decoderFed;

        Generation(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private final TextureView view;
    private final Handler main;
    private final Listener listener;

    /** Written on the main thread; read by the message loop to find out what it may feed. */
    @Nullable
    private volatile Generation generation;
    /** Set once this console has decided it will not decode again; see {@link #disable}. */
    private volatile boolean disabled;
    @Nullable
    private Surface surface;
    /**
     * The decoder currently attached to the surface, whatever generation stood it up. Tracked
     * beside the generation because a new generation replaces the surface and the decoder together
     * -- a guest resize builds a second decoder while the first is still attached -- and the one
     * being replaced has to be released without a reference to the generation that owns it.
     */
    @Nullable
    private H264ConsoleDecoder attachedDecoder;
    private boolean live;
    private boolean stopping;

    public H264ConsolePipeline(@NonNull TextureView view, @NonNull Handler main,
                               @NonNull Listener listener) {
        this.view = view;
        this.main = main;
        this.listener = listener;
    }

    public boolean isLive() {
        return live;
    }

    /**
     * Feeds one encoding-50 rect body, header and all.
     *
     * <p>Called on the RFB message-loop thread, and the blocking inside is the point: a stalled
     * decoder stops this thread, this thread stops draining the socket, and the server -- which
     * only sends what an outstanding request asked for -- stops being asked. That chain is what
     * keeps a slow device showing late frames rather than wrong ones.</p>
     *
     * <p>Nothing thrown, ever. A body that will not parse is a disagreement between the reader that
     * pulled it off the socket and the parser here, not a desynchronised socket -- the reader took
     * exactly the bytes the length declared -- so the connection is fine and only this pipeline has
     * to come down.</p>
     */
    @AnyThread
    public void submitStreamRect(@NonNull byte[] rectBody, int width, int height) {
        if (disabled) return;
        H264RectProtocol.StreamRect rect;
        try {
            rect = H264RectProtocol.parseStreamRect(rectBody);
            if (width <= 0 || height <= 0)
                throw new IOException(fmt("h264 rect at %dx%d has no picture in it", width, height));
        } catch (IOException e) {
            var doomed = generation;
            main.post(() -> teardown(doomed, e));
            return;
        }
        var gen = generation;
        if (gen == null || gen.width != width || gen.height != height) {
            var fresh = new Generation(width, height);
            // Published before the post, so that a rect arriving on the heels of this one finds the
            // generation it is about to be told to wait for rather than making a second one.
            generation = fresh;
            gen = fresh;
            main.post(() -> configure(fresh));
        }
        // Cached before the latch, so that a reset-flagged rect dropped now -- because the surface
        // is not ready this instant -- is still on hand to prime the decoder that stands up a frame
        // later. The reset flag is exactly what marks the rect that carries the parameter sets.
        // decoderFed is not disturbed: it is per-generation, and a mid-stream reset of a decoder
        // that has already been fed must go through the reset path in feed(), not the prime path.
        if (rect.resetsDecoder()) gen.syncFrame = rect.annexB;
        try {
            if (!gen.settled.await(CONFIGURE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "the main thread did not answer a new stream geometry in time");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        var decoder = gen.decoder;
        // Null is the ordinary "there is no window for this picture" -- a backgrounded console, or
        // one whose presentation window has not been built yet. The frame is dropped rather than
        // held, but the sync frame it may have carried is not: it is in gen.syncFrame, and the next
        // frame to reach a live decoder replays it first.
        if (decoder == null || generation != gen) return;
        try {
            feed(gen, decoder, rect);
        } catch (IOException e) {
            var doomed = gen;
            main.post(() -> teardown(doomed, e));
        }
    }

    /**
     * Hands one rect to the decoder, priming it with the cached sync frame the first time.
     *
     * <p>The priming is what closes the black-screen hole. When a decoder is fed for the first
     * time it may be one that stood up after the sync rect went by -- so if this rect is not itself
     * a sync (it does not reset), the cached {@link Generation#syncFrame} goes in ahead of it, and
     * the decoder gets its SPS/PPS before the delta that would otherwise mean nothing. A rect that
     * is a sync needs none of this: it carries its own parameter sets, and a decoder this fresh has
     * no prior context to reset.</p>
     *
     * <p>All on the message-loop thread, so {@code decoderFed} and {@code syncFrame} need no
     * guarding: the one thread that reads them is the one that writes them.</p>
     */
    private void feed(@NonNull Generation gen, @NonNull H264ConsoleDecoder decoder,
                      @NonNull H264RectProtocol.StreamRect rect) throws IOException {
        if (!gen.decoderFed) {
            gen.decoderFed = true;
            if (rect.resetsDecoder()) {
                decoder.submit(rect.annexB);
                return;
            }
            if (gen.syncFrame != null) decoder.submit(gen.syncFrame);
            decoder.submit(rect.annexB);
            return;
        }
        if (rect.resetsDecoder()) decoder.reset();
        decoder.submit(rect.annexB);
    }

    /** Takes the pipeline down deliberately: no reason, no notice beyond the state change. */
    @MainThread
    public void stop() {
        teardown(generation, null);
    }

    /**
     * Takes it down and keeps it down, for a console that has decided it is not going to decode.
     *
     * <p>Needed because the frames keep arriving whatever this object thinks: the stream rides the
     * RFB connection now, so there is no socket to close to make it stop. Only the server can stop
     * sending, and the only thing that makes it stop is a connection that never asked.</p>
     */
    @MainThread
    public void disable() {
        disabled = true;
        stop();
    }

    /**
     * Stands a decoder up for [gen]'s geometry, or arranges to hear about it when one can exist.
     *
     * <p>The surface listener goes on before the availability check and stays on for the whole
     * generation, because the case it exists for is not only "the surface does not exist yet" but
     * also "the surface stopped existing" -- and the second one arrives on a view that was
     * available when this ran.</p>
     */
    @MainThread
    private void configure(@NonNull Generation gen) {
        if (generation != gen || stopping) {
            gen.settled.countDown();
            return;
        }
        // Releases the decoder and surface of the generation this one replaces -- a guest resize
        // reaches here with the previous decoder still attached, and without this it would run on
        // against a surface about to be released and never be freed.
        releaseDecoderAndSurface();
        view.setSurfaceTextureListener(new SurfaceListener(gen));
        view.setVisibility(VISIBLE);
        var texture = view.getSurfaceTexture();
        if (view.isAvailable() && texture != null) attach(gen, texture);
        else gen.settled.countDown();
    }

    @MainThread
    private void attach(@NonNull Generation gen, @NonNull SurfaceTexture texture) {
        if (generation != gen || stopping || gen.decoder != null) return;
        // The decoder writes frames at the coded size the rect announced; the view scales whatever
        // it is given, so the two never have to be made equal.
        texture.setDefaultBufferSize(gen.width, gen.height);
        var target = new Surface(texture);
        surface = target;
        var started = new H264ConsoleDecoder(cause -> main.post(() -> teardown(gen, cause)));
        if (!started.start(target, gen.width, gen.height)) {
            gen.settled.countDown();
            teardown(gen, new NoDecoderException());
            return;
        }
        gen.decoder = started;
        attachedDecoder = started;
        live = true;
        // Counted down only now: a frame waiting on this must find the decoder published, not the
        // latch open and the field still empty. The message loop primes it with the generation's
        // cached sync frame on the first submit, so a decoder that stood up after the sync rect
        // still gets its parameter sets.
        gen.settled.countDown();
        Log.i(TAG, fmt("decoding the console at %dx%d", gen.width, gen.height));
        listener.onStreamLive(gen.width, gen.height);
    }

    /**
     * Puts everything back the way it was, once. Idempotent because it is reached from five places
     * -- the console closing, a rect that would not parse, the decoder failing, a submit that timed
     * out and the surface going away -- and two of them can happen at the same moment.
     *
     * <p>[gen] is the generation the caller believes it is ending. A teardown for one that is no
     * longer current is a message from a decoder that has already been replaced, and doing its
     * bidding would tear down the decoder that replaced it.</p>
     */
    @MainThread
    private void teardown(@Nullable Generation gen, @Nullable Exception cause) {
        if (stopping || generation == null || generation != gen) return;
        stopping = true;
        var wasLive = live;
        live = false;
        if (cause != null) Log.w(TAG, "the console's H.264 stream is down", cause);
        generation = null;
        // Anything parked on this generation is waiting for a decoder that is not coming.
        gen.settled.countDown();
        gen.decoder = null;
        releaseDecoderAndSurface();
        view.setSurfaceTextureListener(null);
        view.setVisibility(GONE);
        stopping = false;
        listener.onStreamGone(wasLive, cause);
    }

    /** Releases whatever decoder is attached to the surface, and the surface, if any. */
    @MainThread
    private void releaseDecoderAndSurface() {
        if (attachedDecoder != null) {
            attachedDecoder.release();
            attachedDecoder = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    /** The decoder's window, watched for the whole life of the generation that asked for it. */
    private final class SurfaceListener implements TextureView.SurfaceTextureListener {
        private final Generation gen;

        SurfaceListener(@NonNull Generation gen) {
            this.gen = gen;
        }

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
            attach(gen, st);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
            // The view moved or resized. The decoder writes at the stream's coded size and the view
            // scales it, so there is nothing to reconfigure.
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
            // The window went away underneath the decoder, which is what backgrounding the console
            // looks like. Reported without a cause because nothing is wrong: the rects keep
            // arriving on a connection that is still up, and the generation they build when the
            // window comes back is what puts the picture on screen again.
            teardown(gen, null);
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
        }
    }
}
