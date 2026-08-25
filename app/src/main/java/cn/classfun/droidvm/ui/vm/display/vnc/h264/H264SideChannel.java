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
     * How long the eight-byte header may take once the socket is open. The server writes it before
     * anything else, so this only ever expires on something that accepted the connection and then
     * had nothing to say -- which must not hang the reader thread forever.
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 3000;
    /** How long the reader waits for the decoder's surface before giving up on the upgrade. */
    private static final long DECODER_READY_TIMEOUT_MS = 5000;

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
    private final CountDownLatch decoderReady = new CountDownLatch(1);
    private final Socket socket = new Socket();
    private volatile boolean closed;
    private Thread thread;

    public H264SideChannel(@NonNull String host, int port, @NonNull Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    /** Starts the reader thread. The listener hears everything, including the failure to connect. */
    public void start() {
        thread = new Thread(this::run, "h264-side-channel");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Releases the reader to start pulling frames. Called once the decoder has a surface and can
     * accept them; until then the server's frames wait in its own send buffer.
     */
    public void decoderReady() {
        decoderReady.countDown();
    }

    /**
     * Ends the connection from the outside. The reader is blocked in a socket read with no timeout
     * -- silence is normal -- so closing the socket is what wakes it, and the exception that
     * results is recognised as this rather than reported as a fault.
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
            // From here on a quiet socket is a screen nobody is changing, which may last as long as
            // the user leaves it alone. A read timeout past the handshake would turn that into a
            // disconnection and a fallback to RFB for a desktop that is merely idle.
            socket.setSoTimeout(0);
            Log.i(TAG, fmt("side channel %s:%d up, %dx%d",
                host, port, header.width, header.height));
            listener.onStreamStarted(header.width, header.height);
            if (!decoderReady.await(DECODER_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                throw new IOException("decoder never came up for the side channel");
            while (!closed) {
                var frame = H264StreamProtocol.readFrame(in);
                if (frame == null) break;
                listener.onFrame(frame);
            }
        } catch (Exception e) {
            // A close() while the reader is parked in read() surfaces as a SocketException. That is
            // this object being taken down, not the stream failing, and reporting it as a failure
            // would put a fallback notice on screen for a console that is simply closing.
            if (!closed) failure = e;
        }
        listener.onStreamEnded(failure);
    }
}
