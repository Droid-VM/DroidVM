// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * The side channel's frames, decoded straight onto a Surface.
 *
 * <p>This is a live screen, not a recording, so every decision here trades smoothness for latency.
 * Output buffers are released for display the moment they exist rather than at a presentation time,
 * the format asks for the platform's low-latency mode, and timestamps are a counter in arrival
 * order -- there is no clock to be faithful to when the thing being shown is happening now.</p>
 *
 * <p>Asynchronous mode, so that a decoder holding a frame back does not also stop the reader from
 * pulling the next one off the socket, and so that a finished frame reaches the screen without
 * anything having to come back and ask for it. That last part is what a synchronous read-feed-drain
 * loop gets wrong on exactly the case this pipeline is built for: with the guest idle, the last
 * frame before the silence would sit undrained until the silence ended.</p>
 */
public final class H264ConsoleDecoder {
    private static final String TAG = "H264ConsoleDecoder";
    private static final String MIME = "video/avc";
    /**
     * How many frames may wait for an input buffer before the submitting thread blocks. Small: the
     * queue exists to cover a momentary hiccup, and anything longer is latency being accumulated
     * rather than absorbed. Blocking is what pushes the backpressure back down the socket.
     */
    private static final int MAX_PENDING = 8;
    /** How long a submit may block before the stream is declared beyond saving. */
    private static final long SUBMIT_TIMEOUT_MS = 2000;
    /** Timestamp step, in microseconds. Only its monotonicity matters. */
    private static final long PTS_STEP_US = 1000;

    /**
     * Reported on the codec's own thread, or on whichever thread was feeding it when it broke, and
     * possibly while this decoder's lock is held -- so implementations post the news somewhere and
     * return rather than tearing anything down inline.
     */
    public interface Listener {
        void onDecoderFailed(@NonNull Exception cause);
    }

    private final Listener listener;
    private final Object lock = new Object();
    private final ArrayDeque<Integer> freeInputs = new ArrayDeque<>();
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private HandlerThread codecThread;
    private MediaCodec codec;
    private long nextPtsUs;
    private boolean released;
    private boolean failed;

    public H264ConsoleDecoder(@NonNull Listener listener) {
        this.listener = listener;
    }

    /**
     * Configures and starts the decoder against [surface]. False means this device could not stand
     * one up at all, which is a fallback to RFB rather than an error to show.
     */
    public boolean start(@NonNull Surface surface, int width, int height) {
        synchronized (lock) {
            if (released) return false;
        }
        try {
            var format = MediaFormat.createVideoFormat(MIME, width, height);
            // The decoder's own guess at a maximum input size is derived from the resolution and a
            // compression ratio no encoder promises. A sync frame of a busy desktop can beat it,
            // and the symptom is one overflowing frame rather than a refusal, so ask for room.
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                Math.max(width * height, 1 << 20));
            // Ask the decoder not to build a reordering pipeline it would have to fill before
            // emitting anything. The stream has no B frames to reorder, and on this path a frame
            // held back for smoothness is a frame the user is waiting on.
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            codecThread = new HandlerThread("h264-decoder");
            codecThread.start();
            codec = MediaCodec.createDecoderByType(MIME);
            codec.setCallback(new Callback(), new android.os.Handler(codecThread.getLooper()));
            codec.configure(format, surface, null, 0);
            codec.start();
            Log.i(TAG, fmt("decoder up for %dx%d", width, height));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not start the H.264 decoder", e);
            release();
            return false;
        }
    }

    /**
     * Hands one frame to the decoder, blocking while the queue is full.
     *
     * <p>Called from the side channel's reader thread, and the blocking is the point: a stalled
     * decoder stops the reader, the reader stops draining the socket, and the server stops being
     * asked for frames. That chain is what keeps a slow device showing late frames rather than
     * wrong ones.</p>
     *
     * @throws IOException when the decoder has failed or stopped keeping up, which the caller turns
     *                     into the end of the stream and thus a fallback to RFB.
     */
    public void submit(@NonNull byte[] frame) throws IOException {
        synchronized (lock) {
            var deadline = System.currentTimeMillis() + SUBMIT_TIMEOUT_MS;
            while (!released && !failed && pending.size() >= MAX_PENDING) {
                var left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new IOException("the H.264 decoder stopped keeping up");
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while feeding the H.264 decoder");
                }
            }
            if (failed) throw new IOException("the H.264 decoder failed");
            if (released) throw new IOException("the H.264 decoder is gone");
            pending.add(frame);
            pump();
        }
    }

    /** Tears the decoder down. Safe to call more than once, and from any thread. */
    public void release() {
        MediaCodec doomed;
        HandlerThread thread;
        synchronized (lock) {
            if (released) return;
            released = true;
            doomed = codec;
            thread = codecThread;
            codec = null;
            codecThread = null;
            pending.clear();
            freeInputs.clear();
            lock.notifyAll();
        }
        if (doomed != null) {
            try {
                doomed.stop();
            } catch (Exception ignored) {
                // A codec that already failed refuses to stop; it still has to be released.
            }
            try {
                doomed.release();
            } catch (Exception ignored) {
                // Nothing left to do about a codec that will not let go.
            }
        }
        if (thread != null) thread.quitSafely();
    }

    /**
     * Moves whatever can move. Always called holding [lock].
     *
     * <p>Both queues are peeked and only dropped once the frame is actually in the codec's hands,
     * so a buffer index cannot be lost on the way past a failure -- the decoder is given a fixed
     * number of them and a leaked one never comes back.</p>
     */
    private void pump() {
        while (!released && !pending.isEmpty() && !freeInputs.isEmpty()) {
            var index = freeInputs.peek();
            var frame = pending.peek();
            if (index == null || frame == null) return;
            try {
                var buffer = codec.getInputBuffer(index);
                if (buffer == null) return;
                buffer.clear();
                buffer.put(frame);
                codec.queueInputBuffer(index, 0, frame.length, nextPtsUs, 0);
                nextPtsUs += PTS_STEP_US;
                freeInputs.poll();
                pending.poll();
                lock.notifyAll();
            } catch (Exception e) {
                fail(e);
                return;
            }
        }
    }

    private void fail(@NonNull Exception cause) {
        synchronized (lock) {
            if (failed || released) return;
            failed = true;
            lock.notifyAll();
        }
        Log.w(TAG, "H.264 decode failed", cause);
        listener.onDecoderFailed(cause);
    }

    private final class Callback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            synchronized (lock) {
                if (released) return;
                freeInputs.add(index);
                pump();
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index,
                                            @NonNull MediaCodec.BufferInfo info) {
            try {
                // true: hand it to the Surface now. There is no presentation schedule to keep --
                // the frame describes the guest's screen as of when it was encoded, so the only
                // right time to show it is immediately.
                codec.releaseOutputBuffer(index, true);
            } catch (Exception e) {
                fail(e);
            }
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            fail(e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            // The guest resized, or the decoder settled on a size of its own. Nothing to do: the
            // Surface is scaled by the view, and the RFB side is what notices a resize and restarts
            // this channel so the header is read again.
            Log.i(TAG, fmt("decoder output format now %s", format));
        }
    }

    /** Whether this decoder is still usable. */
    public boolean isAlive() {
        synchronized (lock) {
            return !released && !failed;
        }
    }
}
