package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264StreamProtocol.Refusal;

/**
 * The retry ladder, read off the policy rather than off a stopwatch.
 *
 * <p>The behaviour being pinned is a rule about the future -- "ask again in five seconds, then ten"
 * -- which is exactly the sort of thing that is otherwise only observable by sitting in front of a
 * console with a VM that is refusing. So the schedule lives in a class with no clock and no Android
 * in it, and the activity's only job is to post what this returns.</p>
 */
public class H264ProbePolicyTest {
    @Test
    public void theLadderClimbsAndThenStopsClimbing() {
        var policy = new H264ProbePolicy();
        assertEquals(5_000, policy.nextDelayMs(transient_()));
        assertEquals(10_000, policy.nextDelayMs(transient_()));
        assertEquals(15_000, policy.nextDelayMs(transient_()));
        // The cap is a cap, not a last rung: a console left open all afternoon still notices the
        // encoder arriving, within fifteen seconds of it doing so.
        assertEquals(15_000, policy.nextDelayMs(transient_()));
        assertEquals(15_000, policy.nextDelayMs(transient_()));
        assertFalse(policy.isGivenUp());
    }

    @Test
    public void aStreamThatCameUpPutsTheLadderBackAtTheBottom() {
        var policy = new H264ProbePolicy();
        policy.nextDelayMs(transient_());
        policy.nextDelayMs(transient_());
        assertEquals(15_000, policy.nextDelayMs(transient_()));
        // Whatever ends a stream that worked is a new fault and inherits nothing from the failures
        // before it -- otherwise one bad minute at the start would leave every later fallback
        // fifteen seconds from recovering.
        policy.onLive();
        assertEquals(5_000, policy.nextDelayMs(transient_()));
    }

    @Test
    public void aHostWithNoEncoderIsNotAskedAgain() {
        var policy = new H264ProbePolicy();
        assertEquals(5_000, policy.nextDelayMs(transient_()));
        assertEquals(H264ProbePolicy.STOP, policy.nextDelayMs(refused("no-encoder no codec here")));
        assertTrue(policy.isGivenUp());
        // And stays given up: a later transient failure must not restart a ladder that was ended
        // by a fact about the host.
        assertEquals(H264ProbePolicy.STOP, policy.nextDelayMs(transient_()));
        assertEquals(H264ProbePolicy.STOP, policy.nextDelayMs(refused("busy try later")));
    }

    @Test
    public void everyOtherRefusalIsWorthAskingAgainAbout() {
        var busy = new H264ProbePolicy();
        assertEquals(5_000, busy.nextDelayMs(refused("busy another client already has the stream")));
        assertFalse(busy.isGivenUp());

        var unknown = new H264ProbePolicy();
        assertEquals(5_000, unknown.nextDelayMs(refused("wedged the encoder is being rebuilt")));
        assertFalse(unknown.isGivenUp());
    }

    @Test
    public void aFailureThatIsNotARefusalIsClassifiedAsTransient() {
        // Nothing listening, a truncated stream, a decoder that could not be built, and the
        // heartbeat running out are all reasons to come back -- none of them is the host declining.
        assertEquals(Refusal.UNKNOWN,
            H264ProbePolicy.refusalOf(new ConnectException("connection refused")));
        assertEquals(Refusal.UNKNOWN,
            H264ProbePolicy.refusalOf(new SocketTimeoutException("read timed out")));
        assertEquals(Refusal.UNKNOWN, H264ProbePolicy.refusalOf(null));
        assertEquals(Refusal.NO_ENCODER,
            H264ProbePolicy.refusalOf(refused("no-encoder none available")));
    }

    private static Exception transient_() {
        return new IOException("the stream ended");
    }

    /** What the parser hands the policy when the server said why. */
    private static Exception refused(String reason) {
        try {
            H264StreamProtocol.readHeader(new java.io.ByteArrayInputStream(concat(
                new byte[]{'D', 'V', 'H', 'X'},
                reason.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                new byte[]{0})));
        } catch (Exception e) {
            return e;
        }
        throw new AssertionError("a refusal did not read as one");
    }

    private static byte[] concat(byte[]... parts) {
        var total = 0;
        for (var part : parts) total += part.length;
        var out = new byte[total];
        var at = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
