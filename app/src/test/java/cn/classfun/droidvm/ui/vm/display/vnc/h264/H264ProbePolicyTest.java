package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264ProbePolicy.Mode;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264ProbePolicy.Order;

/**
 * The client rules of {@code plans/H264_SINGLE_PORT.md} section 1, read off the policy rather than
 * off a stopwatch.
 *
 * <p>What is being pinned is a set of statements about the future -- "give up after five seconds of
 * this", "declare it dead after ten seconds of that" -- which is otherwise only observable by
 * sitting in front of a console with a VM in the right state. So the rules live in a class with no
 * clock and no Android in it, and the time is a parameter every one of these tests supplies.</p>
 */
public class H264ProbePolicyTest {
    private static final long T0 = 100_000;

    @Test
    public void aCapabilitiesRectSayingAvailablePutsTheConsoleOnTheDecoder() {
        var p = connected();
        assertEquals(Mode.WAITING, p.mode());
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0 + 40);
        assertEquals(Mode.DECODING, p.mode());
        assertFalse(p.isPermanent());
    }

    @Test
    public void warmingIsAWaitAndNotAnAnswer() {
        var p = connected();
        p.onCapsRect(H264RectProtocol.CAPS_WARMING, T0 + 40);
        assertEquals(Mode.WAITING, p.mode());
        // And the five seconds do not run out underneath it: the server said it would say more, so
        // there is no silence to draw a conclusion from.
        assertEquals(Order.NOTHING, p.tick(T0 + 60_000));
        assertEquals(Mode.WAITING, p.mode());
        assertFalse(p.isPermanent());

        // Minutes later the encoder comes up, and the console is not still waiting for a timer.
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0 + 90_000);
        assertEquals(Mode.DECODING, p.mode());
        // The ten seconds are measured from here, not from the connect: a stream announced after a
        // long warm-up must not be declared dead on the tick after it was announced.
        assertEquals(Order.NOTHING, p.tick(T0 + 95_000));
        assertEquals(Mode.DECODING, p.mode());
    }

    @Test
    public void aHostWithNoEncoderEndsTheQuestionForGood() {
        var p = connected();
        p.onCapsRect(H264RectProtocol.CAPS_NO_ENCODER, T0 + 40);
        assertEquals(Mode.PIXELS, p.mode());
        assertTrue(p.isPermanent());
        assertTrue(p.saidNoEncoder());
        // Nothing reopens it while this console is open -- not a later capabilities rect, not
        // frames. The host does not grow an encoder while the VM runs.
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0 + 5_000);
        assertEquals(Mode.PIXELS, p.mode());
        p.onStreamRect(T0 + 6_000);
        assertEquals(Mode.PIXELS, p.mode());
        // Not even a reconnection: it is a fact about the host, not about the connection.
        p.onConnected(T0 + 20_000);
        assertEquals(Mode.PIXELS, p.mode());
        assertTrue(p.saidNoEncoder());
    }

    @Test
    public void anUnreadableCapabilitiesValueIsAWaitAndNotARefusal() {
        // A newer host's new vocabulary must not permanently downgrade an old client, which is the
        // same rule the version byte follows on the wire.
        var p = connected();
        p.onCapsRect(9, T0 + 40);
        assertEquals(Mode.WAITING, p.mode());
        assertFalse(p.isPermanent());
        assertFalse(p.saidNoEncoder());
    }

    @Test
    public void anOldServerIsFiveSecondsOfSilenceAndThenThePixelPath() {
        // The server ignored both encodings and is serving pixels. There is no capabilities rect
        // coming, and the console must not sit in "warming" for the rest of its life.
        var p = connected();
        assertEquals(Order.NOTHING, p.tick(T0 + 4_999));
        assertEquals(Mode.WAITING, p.mode());
        assertEquals(Order.NOTHING, p.tick(T0 + H264ProbePolicy.CAPS_GRACE_MS));
        assertEquals(Mode.PIXELS, p.mode());
    }

    @Test
    public void theSilenceVerdictIsAGuessAndEvidenceOverturnsIt() {
        var p = connected();
        p.tick(T0 + H264ProbePolicy.CAPS_GRACE_MS);
        assertEquals(Mode.PIXELS, p.mode());
        // Never permanent, and never announced: an ordinary VNC server has not refused anything,
        // so there is nothing to tell the user and nothing to stop asking about.
        assertFalse(p.isPermanent());
        assertFalse(p.saidNoEncoder());

        // A capabilities rect that merely arrived late outranks a conclusion drawn from its
        // absence.
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0 + 8_000);
        assertEquals(Mode.DECODING, p.mode());
    }

    @Test
    public void framesAlsoOverturnTheSilenceVerdict() {
        var p = connected();
        p.tick(T0 + H264ProbePolicy.CAPS_GRACE_MS);
        assertEquals(Mode.PIXELS, p.mode());
        p.onStreamRect(T0 + 6_000);
        assertEquals(Mode.DECODING, p.mode());
    }

    @Test
    public void aHeartbeatIsLivenessAndNotAReasonToDecode() {
        var p = connected();
        p.onCapsRect(H264RectProtocol.CAPS_WARMING, T0 + 40);
        // There is nothing in a heartbeat to put on screen, so it must not flip the console onto a
        // decoder that would have no frames to show.
        p.onHeartbeat(T0 + 3_000);
        assertEquals(Mode.WAITING, p.mode());
        // It does say the server knows about the pseudo-encoding, so the five seconds are off.
        assertEquals(Order.NOTHING, p.tick(T0 + 30_000));
        assertEquals(Mode.WAITING, p.mode());
    }

    @Test
    public void heartbeatsKeepAnIdleStreamAlive() {
        var p = connected();
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0);
        // A still screen produces no frames, which is why the host beats every three seconds. Half
        // a minute of that is a console that is fine, not one that is frozen.
        for (var t = T0 + 3_000; t <= T0 + 30_000; t += 3_000) {
            p.onHeartbeat(t);
            assertEquals(Order.NOTHING, p.tick(t + 1_000));
        }
        assertEquals(Mode.DECODING, p.mode());
    }

    @Test
    public void tenSecondsOfNothingAtAllIsADeadStream() {
        var p = connected();
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0);
        p.onStreamRect(T0 + 1_000);
        assertEquals(Order.NOTHING, p.tick(T0 + 1_000 + H264ProbePolicy.SILENCE_MS - 1));
        assertEquals(Mode.DECODING, p.mode());
        // No frame and no beat: the host is gone or wedged, however alive the socket looks.
        assertEquals(Order.RECONNECT, p.tick(T0 + 1_000 + H264ProbePolicy.SILENCE_MS));
        assertEquals(Mode.WAITING, p.mode());
        assertFalse(p.isPermanent());
    }

    @Test
    public void theSameDeadStreamIsNotReportedOnEveryTick() {
        var p = connected();
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0);
        assertEquals(Order.RECONNECT, p.tick(T0 + H264ProbePolicy.SILENCE_MS));
        // The mode left DECODING, so there is nothing for the silence clock to be about until a
        // stream comes back.
        assertEquals(Order.NOTHING, p.tick(T0 + H264ProbePolicy.SILENCE_MS + 1_000));
        assertEquals(Order.NOTHING, p.tick(T0 + 120_000));
    }

    @Test
    public void aSecondDeadStreamIsThePixelPathForGood() {
        var p = connected();
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0);
        assertEquals(Order.RECONNECT, p.tick(T0 + H264ProbePolicy.SILENCE_MS));

        // The reconnect this asked for. The counter deliberately survives it -- reset there, the
        // console would ask for reconnects forever.
        var t = T0 + 20_000;
        p.onConnected(t);
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, t);
        assertEquals(Mode.DECODING, p.mode());
        assertEquals(Order.NOTHING, p.tick(t + H264ProbePolicy.SILENCE_MS));
        assertEquals(Mode.PIXELS, p.mode());
        assertTrue(p.isPermanent());
        // And not announced as a host refusal, because it was not one.
        assertFalse(p.saidNoEncoder());
        // A third connection inherits the verdict rather than starting the cycle again.
        p.onConnected(t + 60_000);
        assertEquals(Mode.PIXELS, p.mode());
    }

    @Test
    public void aDeviceWithNoDecoderIsPermanentAndIsNotTheHostsFault() {
        var p = connected();
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0);
        assertEquals(Mode.DECODING, p.mode());
        p.onDecoderUnsupported();
        assertEquals(Mode.PIXELS, p.mode());
        assertTrue(p.isPermanent());
        assertTrue(p.isDecoderUnsupported());
        // Told apart from the host having no encoder: the console withdraws the encodings for this
        // one, and says nothing about the host.
        assertFalse(p.saidNoEncoder());
        p.onConnected(T0 + 30_000);
        assertEquals(Mode.PIXELS, p.mode());
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0 + 30_100);
        assertEquals(Mode.PIXELS, p.mode());
    }

    @Test
    public void nothingIsDecidedBeforeAConnectionOrAfterOneEnds() {
        var p = new H264ProbePolicy();
        // No connection, no clocks: an un-started policy that timed out would put a console on the
        // pixel path before it had asked anything.
        assertEquals(Order.NOTHING, p.tick(T0 + 600_000));
        assertEquals(Mode.WAITING, p.mode());

        p.onConnected(T0);
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0);
        assertEquals(Mode.DECODING, p.mode());
        p.onDisconnected();
        assertEquals(Mode.WAITING, p.mode());
        assertEquals(Order.NOTHING, p.tick(T0 + 600_000));
    }

    @Test
    public void aReconnectionRestartsTheFiveSecondClock() {
        var p = connected();
        p.onCapsRect(H264RectProtocol.CAPS_AVAILABLE, T0);
        p.onDisconnected();
        var t = T0 + 60_000;
        p.onConnected(t);
        assertEquals(Mode.WAITING, p.mode());
        assertEquals(Order.NOTHING, p.tick(t + H264ProbePolicy.CAPS_GRACE_MS - 1));
        assertEquals(Mode.WAITING, p.mode());
        assertEquals(Order.NOTHING, p.tick(t + H264ProbePolicy.CAPS_GRACE_MS));
        assertEquals(Mode.PIXELS, p.mode());
    }

    private static H264ProbePolicy connected() {
        var p = new H264ProbePolicy();
        p.onConnected(T0);
        return p;
    }
}
