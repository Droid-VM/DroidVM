// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One connection to a screen's H.264 side channel: connect, read the header, hand every frame to
 * the listener, and say why it ended.
 *
 * <p><b>The connect attempt is the probe.</b> Nothing in the config can promise that an encoder is
 * actually standing behind this port -- the ceiling says it is permitted, the host decides whether
 * it was built -- so the honest question is asked by asking it, with a timeout short enough that a
 * console opening on a VM without one does not visibly wait. Whatever the answer, the RFB
 * connection is already up and showing the screen; this only ever upgrades it.</p>
 *
 * <p>The reader thread does not start reading frames until the decoder says it is ready. That is a
 * latch rather than a queue on purpose: the frames the server would have sent stay in its socket
 * buffer, where TCP already has a mechanism for holding them, instead of in a list here that would
 * have to decide what to drop. Dropping is the wrong answer anyway -- every P frame after a dropped
 * one decodes into rubbish, so a decoder that cannot keep up must be torn down rather than fed
 * selectively.</p>
 *
 * <p><b>Every wait in here is bounded, and that is new.</b> The read on a live stream used to have
 * no timeout at all, on the reasoning that a still screen sends nothing and must not be mistaken
 * for a dead one. The reasoning was right and the consequence was a console that froze: a host that
 * stopped mid-stream left this thread parked in {@code read} forever, holding a socket the server
 * still counted as its one client, while the RFB updates that would have kept the picture moving
 * stayed suppressed for a decoder nothing was ever going to feed again. The heartbeat is what
 * removed the reason for the unbounded wait, so the wait is bounded now -- and {@link #close} no
 * longer trusts a single {@code Socket.close} to have been enough, because the whole failure was
 * something staying alive that everyone had agreed was over.</p>
 */
public final class H264SideChannel implements Closeable {
    private static final String TAG = "H264SideChannel";

    /**
     * How long the connect may take before the console gives up and stays on RFB. Short because
     * this is a loopback port on the same phone: anything that is going to answer answers at once,
     * and everything else is a port with nothing behind it.
     */
    private static final int CONNECT_TIMEOUT_MS = 1500;
    /**
     * How long the header may take once the socket is open.
     *
     * <p>Longer than it looks like it should be, because the server does not write the header on
     * accept: it parks the connection until a frame has arrived to encode, since the geometry the
     * header states is a property of that frame. Its own wait for one is ten seconds, after which
     * it refuses in words -- so anything shorter here would hang up on a guest that was merely
     * between frames and, worse, would replace a refusal that says why with a timeout that does
     * not. Waiting costs nothing visible: RFB is already on screen the whole time.</p>
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 12000;
    /**
     * How long a live stream may say nothing before it is declared dead.
     *
     * <p>The host beats every three seconds it has nothing else to send, so this is three intervals
     * plus change: long enough that a late beat under load is not a funeral, short enough that a
     * frozen console is measured in seconds. It is the only thing standing between a host that
     * stopped and a picture that never moves again.</p>
     */
    private static final int READ_TIMEOUT_MS = 10000;
    /** How long the reader waits for the decoder's surface before giving up on the upgrade. */
    private static final long DECODER_READY_TIMEOUT_MS = 5000;
    /**
     * How long {@link #close} waits for the reader to actually be gone.
     *
     * <p>It is on the main thread, so it cannot be the read timeout; it is long enough for a thread
     * that has already been woken to finish unwinding, and its failure is loud rather than silent
     * because a reader that outlives its channel is exactly the bug this is here to end.</p>
     */
    private static final long JOIN_TIMEOUT_MS = 300;

    /** Everything here is called on the channel's own reader thread. */
    public interface Listener {
        /** The header arrived: this is the geometry the decoder has to be configured for. */
        void onStreamStarted(int width, int height);

        /** One frame's worth of Annex-B NAL units, in arrival order. */
        void onFrame(@NonNull byte[] annexB) throws IOException;

        /**
         * The stream is over and this channel is finished. [cause] is null for the two orderly
         * endings -- the server closed, or {@link #close} was called -- and the failure otherwise.
         */
        void onStreamEnded(@Nullable Exception cause);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final int readTimeoutMs;
    private final CountDownLatch decoderReady = new CountDownLatch(1);
    private final Socket socket = new Socket();
    private volatile boolean closed;
    private volatile Thread thread;

    public H264SideChannel(@NonNull String host, int port, @NonNull Listener listener) {
        this(host, port, listener, READ_TIMEOUT_MS);
    }

    /**
     * The read timeout is a parameter only so that a test can watch a stream die of silence without
     * spending ten seconds doing it. Everything else uses {@link #READ_TIMEOUT_MS}.
     */
    H264SideChannel(@NonNull String host, int port, @NonNull Listener listener, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.listener = listener;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Starts the reader thread. The listener hears everything, including the failure to connect. */
    public void start() {
        var reader = new Thread(this::run, "h264-side-channel");
        reader.setDaemon(true);
        thread = reader;
        reader.start();
    }

    /** Whether the reader thread is gone. The test for "a closed channel leaves nothing". */
    public boolean isStopped() {
        var reader = thread;
        return reader == null || !reader.isAlive();
    }

    /**
     * Releases the reader to start pulling frames. Called once the decoder has a surface and can
     * accept them; until then the server's frames wait in its own send buffer.
     */
    public void decoderReady() {
        decoderReady.countDown();
    }

    /**
     * Ends the connection from the outside, and does not return until it believes that happened.
     *
     * <p>Three things rather than one, because the failure this replaces was a socket and a thread
     * that outlived every object that knew about them. Closing the socket is what wakes a reader
     * parked in {@code read}; the interrupt is for the two waits a closed socket says nothing to --
     * the decoder-ready latch and the decoder's own submit queue; and the join is the part that
     * makes "closed" a fact rather than a request. A reader still alive after all three is logged
     * as such, because it is bounded from now on either way -- the read timeout guarantees it
     * unwinds within one interval -- but it is not the sort of thing that should happen quietly.</p>
     */
    @Override
    public void close() {
        closed = true;
        decoderReady.countDown();
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed, or never opened. Either way there is nothing left to release.
        }
        var reader = thread;
        // Never from the reader itself: onStreamEnded runs on this thread, and a listener that
        // closed its channel from inside it would be waiting for itself.
        if (reader == null || reader == Thread.currentThread()) return;
        reader.interrupt();
        try {
            reader.join(JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (reader.isAlive())
            Log.w(TAG, fmt("the reader for %s:%d outlived its close by more than %dms",
                host, port, JOIN_TIMEOUT_MS));
    }

    private void run() {
        Exception failure = null;
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            // Every frame is a whole picture the moment it is written; there is nothing a Nagle
            // delay could usefully coalesce it with, and the cost of waiting is a frame of latency.
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            var in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
            var header = H264StreamProtocol.readHeader(in);
            // Past the handshake the silence has a floor: the host beats every three seconds it has
            // nothing to send, so a read that times out is a statement about the host and not about
            // an idle desktop. That is the whole reason the beat exists.
            socket.setSoTimeout(readTimeoutMs);
            Log.i(TAG, fmt("side channel %s:%d up, %dx%d",
                host, port, header.width, header.height));
            listener.onStreamStarted(header.width, header.height);
            if (!decoderReady.await(DECODER_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                throw new IOException("decoder never came up for the side channel");
            while (!closed) {
                var frame = H264StreamProtocol.readFrame(in);
                if (frame == null) break;
                // A heartbeat has no picture in it. It has already done its job by arriving: the
                // read returned, so the timeout did not, and there is nothing to decode.
                if (frame.length == 0) continue;
                listener.onFrame(frame);
            }
        } catch (SocketTimeoutException e) {
            // The beats stopped. Not an idle screen -- an idle screen beats -- so this is the host
            // being gone or wedged, and the console has to go back to RFB rather than keep showing
            // the last picture it was sent.
            if (!closed) failure = new IOException(fmt(
                "the H.264 side channel went silent for %dms", readTimeoutMs), e);
        } catch (Exception e) {
            // A close() while the reader is parked in read() surfaces as a SocketException. That is
            // this object being taken down, not the stream failing, and reporting it as a failure
            // would put a fallback notice on screen for a console that is simply closing.
            if (!closed) failure = e;
        }
        listener.onStreamEnded(failure);
    }
}
