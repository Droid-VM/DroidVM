package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The side channel's lifecycle, against a real loopback server.
 *
 * <p>Everything here is about something outliving the thing that owned it, which is why it is worth
 * a socket and a thread rather than a mock: the end-to-end failure this covers was a console that
 * had been closed still holding the server's one client slot, and a stalled stream that froze the
 * picture because the reader was parked in a {@code read} with no timeout while RFB stayed
 * suppressed for a decoder nothing would ever feed again. Neither is visible in a parser test --
 * both are about who is still alive afterwards.</p>
 *
 * <p>The read timeout is passed in rather than waited out, so the death-by-silence case costs a
 * few hundred milliseconds instead of the ten seconds it takes in the app.</p>
 */
public class H264SideChannelTest {
    private static final int TIMEOUT_MS = 400;

    private final List<AutoCloseable> open = new ArrayList<>();

    @After
    public void closeEverything() {
        for (var c : open) {
            try {
                c.close();
            } catch (Exception ignored) {
                // Teardown of a test that may have closed it already.
            }
        }
    }

    @Test
    public void aStreamThatGoesSilentIsDeadRatherThanIdle() throws Exception {
        // The stall. The server sends a header and then nothing at all -- not even the heartbeat
        // it is supposed to send every three seconds -- which is what a wedged or vanished host
        // looks like from here. Before the timeout this parked forever and the console froze with
        // the last frame on it, because the RFB updates that would have kept the picture moving
        // were still suppressed for the decoder.
        var ended = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var server = serve(peer -> {
            write(peer, header(640, 480));
            park();
        });
        var channel = connect(server, new RecordingListener(ended, failure) {
            @Override
            public void onStreamStarted(int width, int height) {
                super.onStreamStarted(width, height);
                ready();
            }
        });
        assertTrue("the stream never ended", ended.await(5, TimeUnit.SECONDS));
        assertNotNull("a silent stream reported no failure", failure.get());
        assertTrue(failure.get().getMessage(), failure.get().getMessage().contains("silent"));
        assertTrue(failure.get().getCause() instanceof SocketTimeoutException);
        channel.close();
        assertTrue(channel.isStopped());
    }

    @Test
    public void heartbeatsAreSilenceThatDoesNotCount() throws Exception {
        // The other half of the same contract: a screen nobody is touching sends no frames, and
        // must not be mistaken for the case above. The beats are what makes the timeout legible,
        // and they are not pictures -- nothing is handed to a decoder.
        var ended = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var frames = new ArrayList<byte[]>();
        // Beats until the client hangs up, at a quarter of the read timeout -- the same ratio the
        // host keeps, three seconds against ten. A server that stopped beating partway through
        // would be testing the case above with extra steps.
        var server = serve(peer -> {
            write(peer, header(640, 480));
            while (true) {
                write(peer, frameHeader(0));
                sleep(TIMEOUT_MS / 4);
            }
        });
        var channel = connect(server, new RecordingListener(ended, failure) {
            @Override
            public void onStreamStarted(int width, int height) {
                super.onStreamStarted(width, height);
                ready();
            }

            @Override
            public void onFrame(@NonNull byte[] annexB) {
                synchronized (frames) {
                    frames.add(annexB);
                }
            }
        });
        // Well past the read timeout, and the stream is still up because the beats keep arriving.
        assertFalse("beats did not hold the stream open",
            ended.await(TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS));
        synchronized (frames) {
            assertTrue("a heartbeat was handed to the decoder", frames.isEmpty());
        }
        channel.close();
        assertTrue(channel.isStopped());
    }

    @Test
    public void aClosedChannelLeavesNothingBehind() throws Exception {
        // The leak, stated as the thing that has to be true afterwards. The reader is parked in a
        // read on a live, quiet stream -- the hardest case to wake -- and close() has to end both
        // the thread and the connection the server is counting.
        var started = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var peerSeen = new AtomicReference<Socket>();
        var server = serve(peer -> {
            peerSeen.set(peer);
            write(peer, header(1280, 720));
            park();
        });
        var channel = connect(server, new RecordingListener(ended, failure) {
            @Override
            public void onStreamStarted(int width, int height) {
                super.onStreamStarted(width, height);
                ready();
                started.countDown();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        channel.close();
        // close() does not return until it believes the reader is gone, so this is not a poll.
        assertTrue("the reader outlived its channel", channel.isStopped());
        assertTrue("close() did not report the ending", ended.await(2, TimeUnit.SECONDS));
        assertNull("a deliberate close was reported as a fault", failure.get());
        // And the server sees the connection go, which is the half the host counts: its single
        // client slot is only freed by this end actually closing the socket.
        var peer = peerSeen.get();
        assertNotNull(peer);
        peer.setSoTimeout(2000);
        assertEquals("the server's end stayed open", -1, peer.getInputStream().read());
    }

    @Test
    public void closingBeforeTheReaderEvenConnectsAlsoLeavesNothing() throws Exception {
        // The race the join exists for: stop() arriving while the reader is still on its way into
        // connect(). Nothing has been read yet, so there is no blocked read for a socket close to
        // wake -- and a reader that gets past this point unnoticed is a connection the server will
        // hold open against the next console.
        var ended = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var server = serve(peer -> {
            write(peer, header(640, 480));
            park();
        });
        var channel = connect(server, new RecordingListener(ended, failure) {
            @Override
            public void onStreamStarted(int width, int height) {
                super.onStreamStarted(width, height);
                ready();
            }
        });
        channel.close();
        assertTrue("the reader outlived its channel", channel.isStopped());
        assertTrue(ended.await(2, TimeUnit.SECONDS));
        assertNull("closing during the connect was reported as a fault", failure.get());
    }

    @Test
    public void aRefusalEndsTheStreamWithItsReasonIntact() throws Exception {
        // The silent refusal: before this, DVHX was just an unrecognised magic and every refusal
        // reached the console as "not an H.264 side channel", indistinguishable from the wrong
        // server answering. The reason is what decides whether the console ever asks again.
        var ended = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var server = serve(peer -> {
            write(peer, refusal("no-encoder this device has no encoder we can use"));
            peer.close();
        });
        connect(server, new RecordingListener(ended, failure));
        assertTrue(ended.await(5, TimeUnit.SECONDS));
        var cause = failure.get();
        assertTrue(String.valueOf(cause), cause instanceof H264StreamProtocol.RefusedException);
        assertEquals(H264StreamProtocol.Refusal.NO_ENCODER,
            ((H264StreamProtocol.RefusedException) cause).refusal);
    }

    // ---- fixtures ----

    /** A listener that records the ending, and lets the reader past the decoder latch on demand. */
    private static class RecordingListener implements H264SideChannel.Listener {
        private final CountDownLatch ended;
        private final AtomicReference<Exception> failure;
        H264SideChannel channel;

        RecordingListener(CountDownLatch ended, AtomicReference<Exception> failure) {
            this.ended = ended;
            this.failure = failure;
        }

        /** Stands in for the decoder having a Surface. */
        void ready() {
            channel.decoderReady();
        }

        @Override
        public void onStreamStarted(int width, int height) {
        }

        @Override
        public void onFrame(@NonNull byte[] annexB) throws IOException {
        }

        @Override
        public void onStreamEnded(@Nullable Exception cause) {
            failure.set(cause);
            ended.countDown();
        }
    }

    private interface PeerHandler {
        void handle(Socket peer) throws Exception;
    }

    /** A one-shot loopback server that runs [handler] against whoever connects. */
    private ServerSocket serve(PeerHandler handler) throws IOException {
        var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        open.add(server);
        var thread = new Thread(() -> {
            try (var peer = server.accept()) {
                handler.handle(peer);
            } catch (Exception ignored) {
                // The client hung up, or the test finished. Either is the end of this server.
            }
        }, "h264-test-server");
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private H264SideChannel connect(ServerSocket server, RecordingListener listener) {
        var channel = new H264SideChannel(
            server.getInetAddress().getHostAddress(), server.getLocalPort(), listener, TIMEOUT_MS);
        listener.channel = channel;
        open.add(channel);
        channel.start();
        return channel;
    }

    private static void write(Socket peer, byte[] bytes) throws IOException {
        OutputStream out = peer.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    /** Says nothing until the far end gives up or goes away. */
    private static void park() {
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] header(int width, int height) {
        return new byte[]{
            'D', 'V', 'H', '2',
            (byte) (width & 0xFF), (byte) ((width >> 8) & 0xFF),
            (byte) (height & 0xFF), (byte) ((height >> 8) & 0xFF)};
    }

    private static byte[] frameHeader(int length) {
        return new byte[]{
            (byte) (length & 0xFF), (byte) ((length >> 8) & 0xFF),
            (byte) ((length >> 16) & 0xFF), (byte) ((length >> 24) & 0xFF)};
    }

    private static byte[] refusal(String reason) {
        var text = reason.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var out = new byte[4 + text.length + 1];
        out[0] = 'D';
        out[1] = 'V';
        out[2] = 'H';
        out[3] = 'X';
        System.arraycopy(text, 0, out, 4, text.length);
        return out;
    }
}
