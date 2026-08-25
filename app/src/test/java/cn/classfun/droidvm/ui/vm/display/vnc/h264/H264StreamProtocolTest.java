package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The side channel's framing, which is the only part of the H.264 console path a JVM can run: the
 * rest is a socket, a codec and a Surface, and none of the three exists here.
 *
 * <p>So the framing is where the failure modes were put. Every one of them is a way for a stream to
 * end that is <em>not</em> the ordinary end, and telling them apart is what decides whether the
 * console falls back to RFB cleanly or feeds a decoder the wrong bytes: a truncation is not a
 * shorter frame, a length that cannot be right is not a frame to allocate for, and a socket that
 * hands over four bytes when eight were asked for has not delivered a header.</p>
 *
 * <p>The short-read case is the one that would otherwise be found in production only: a single
 * {@code read} on a socket may return fewer bytes than asked for at any time, so a parser that
 * treats one read as one message works on loopback and on a quiet phone, and comes apart under
 * load. It is checked here against a stream that deliberately dribbles.</p>
 */
public class H264StreamProtocolTest {
    @Test
    public void theHeaderCarriesTheGeometryTheDecoderIsConfiguredFor() throws IOException {
        var header = H264StreamProtocol.readHeader(stream(header(1920, 1080)));
        assertEquals(1920, header.width);
        assertEquals(1080, header.height);
    }

    @Test
    public void theHeaderIsLittleEndianLikeTheRestOfTheStream() throws IOException {
        // 0x0500 is 1280 with the bytes the other way round, so a big-endian reader would pass the
        // first test and fail this one.
        var raw = new byte[]{'D', 'V', 'H', '2', (byte) 0x00, (byte) 0x05, (byte) 0xD0, (byte) 0x02};
        var header = H264StreamProtocol.readHeader(stream(raw));
        assertEquals(1280, header.width);
        assertEquals(720, header.height);
    }

    @Test
    public void aTruncatedHeaderIsNotASmallerHeader() throws IOException {
        // Four bytes is the magic and nothing else. Reading it as a header would configure a
        // decoder for whatever happened to be in the rest of the buffer.
        var raw = new byte[]{'D', 'V', 'H', '2'};
        var e = assertThrows(EOFException.class,
            () -> H264StreamProtocol.readHeader(stream(raw)));
        assertTrue(e.getMessage(), e.getMessage().contains("header"));
    }

    @Test
    public void anEmptyStreamIsATruncatedHeaderToo() {
        // What a server refusing a second client looks like: the connection is accepted and then
        // closed with nothing written. It has to be an ending, not a wait.
        assertThrows(EOFException.class,
            () -> H264StreamProtocol.readHeader(stream(new byte[0])));
    }

    @Test
    public void somethingElseAnsweringOnThePortIsRefusedByItsMagic() {
        // The port is a number derived from another number, so the wrong thing answering it is a
        // real possibility. It must not be decoded as video.
        var e = assertThrows(IOException.class, () -> H264StreamProtocol.readHeader(
            stream(new byte[]{'R', 'F', 'B', ' ', 0, 0, 0, 0})));
        assertTrue(e.getMessage(), e.getMessage().contains("magic"));
    }

    @Test
    public void aHeaderNoDecoderCouldBeConfiguredForIsRefused() throws IOException {
        // Zero is what an uninitialised field reads as, and the guard above is what keeps a
        // desynchronised stream from being handed to MediaCodec as a 60000-pixel-wide video.
        assertThrows(IOException.class,
            () -> H264StreamProtocol.readHeader(stream(header(0, 1080))));
        assertThrows(IOException.class,
            () -> H264StreamProtocol.readHeader(stream(header(1920, 0))));
        assertThrows(IOException.class, () -> H264StreamProtocol.readHeader(
            stream(header(H264StreamProtocol.MAX_DIMENSION + 1, 1080))));
        // The largest one that is still allowed, so the bound is a bound rather than an off-by-one.
        assertEquals(H264StreamProtocol.MAX_DIMENSION, H264StreamProtocol.readHeader(
            stream(header(H264StreamProtocol.MAX_DIMENSION, 16))).width);
    }

    @Test
    public void framesComeBackWholeAndInOrder() throws IOException {
        var first = bytes(0x00, 0x00, 0x00, 0x01, 0x67, 0x42);
        var second = bytes(0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84);
        var in = stream(concat(header(640, 480), frame(first), frame(second)));
        H264StreamProtocol.readHeader(in);
        assertArrayEquals(first, H264StreamProtocol.readFrame(in));
        assertArrayEquals(second, H264StreamProtocol.readFrame(in));
        // Null, not an exception: the server closing between frames is how this ends normally --
        // the VM stopped, or the console did -- and it must not look like a fault on screen.
        assertNull(H264StreamProtocol.readFrame(in));
    }

    @Test
    public void aStreamThatStopsInsideAFrameIsATruncation() {
        // The difference from the case above is only where the bytes ran out, and it is the whole
        // difference between a clean fallback and a decoder fed half a picture.
        var in = stream(concat(frameHeaderOnly(64), bytes(0x00, 0x00, 0x01)));
        var e = assertThrows(EOFException.class, () -> H264StreamProtocol.readFrame(in));
        assertTrue(e.getMessage(), e.getMessage().contains("frame body"));
    }

    @Test
    public void aStreamThatStopsInsideTheLengthPrefixIsAlsoATruncation() {
        // One byte in is past the boundary: something was going to be said about a frame.
        var e = assertThrows(EOFException.class,
            () -> H264StreamProtocol.readFrame(stream(bytes(0x10, 0x00))));
        assertTrue(e.getMessage(), e.getMessage().contains("frame length"));
    }

    @Test
    public void aLengthNoFrameCouldHaveEndsTheStreamInsteadOfBeingAllocatedFor() {
        // The guard exists because the length prefix is the one number here that nothing else can
        // be checked against. Read as unsigned, so the high bit is a huge frame rather than a
        // negative one -- which is the reading that would otherwise reach new byte[].
        var oversize = H264StreamProtocol.MAX_FRAME_BYTES + 1;
        var e = assertThrows(IOException.class,
            () -> H264StreamProtocol.readFrame(stream(frameHeaderOnly(oversize))));
        assertTrue(e.getMessage(), e.getMessage().contains("guard"));
        assertThrows(IOException.class, () -> H264StreamProtocol.readFrame(
            stream(bytes(0xFF, 0xFF, 0xFF, 0xFF))));
    }

    // ---- the heartbeat ----

    @Test
    public void aZeroLengthFrameIsAHeartbeatAndNotAPicture() throws IOException {
        // This is the one meaning in the format that reversed. It used to be an error, on the
        // reasoning that no NAL unit is empty; the host now writes exactly this every three
        // seconds it has nothing else to say, and it is what a read timeout is measured against.
        var beat = H264StreamProtocol.readFrame(stream(frameHeaderOnly(0)));
        assertNotNull(beat);
        assertEquals(0, beat.length);
    }

    @Test
    public void heartbeatsDoNotDisturbTheFramesAroundThem() throws IOException {
        // The beat is a frame in the framing and nothing in the stream: a reader that skipped it by
        // consuming the wrong number of bytes would desynchronise here rather than at the beat.
        var payload = bytes(0x00, 0x00, 0x00, 0x01, 0x65, 0x42);
        var in = stream(concat(
            header(640, 480), frameHeaderOnly(0), frame(payload), frameHeaderOnly(0)));
        H264StreamProtocol.readHeader(in);
        assertEquals(0, H264StreamProtocol.readFrame(in).length);
        assertArrayEquals(payload, H264StreamProtocol.readFrame(in));
        assertEquals(0, H264StreamProtocol.readFrame(in).length);
        assertNull(H264StreamProtocol.readFrame(in));
    }

    // ---- refusals, and the token that decides whether to ask again ----

    @Test
    public void aRefusalIsAnAnswerRatherThanAStrangerOnThePort() {
        var e = assertThrows(H264StreamProtocol.RefusedException.class,
            () -> H264StreamProtocol.readHeader(stream(refusal("busy someone else has it"))));
        assertEquals(H264StreamProtocol.Refusal.BUSY, e.refusal);
        assertEquals("busy someone else has it", e.reason);
        // The sentence is for a log; the console must not be reading meaning out of it.
        assertTrue(e.getMessage(), e.getMessage().contains("busy someone else has it"));
    }

    @Test
    public void onlyTheLeadingTokenDecidesWhetherToAskAgain() {
        assertEquals(H264StreamProtocol.Refusal.NO_ENCODER, H264StreamProtocol.Refusal
            .fromReason("no-encoder this device has no encoder we can use"));
        assertEquals(H264StreamProtocol.Refusal.BUSY, H264StreamProtocol.Refusal
            .fromReason("busy another client already has the stream"));
        // A token with nothing after it is still a token.
        assertEquals(H264StreamProtocol.Refusal.NO_ENCODER,
            H264StreamProtocol.Refusal.fromReason("no-encoder"));
        // A prefix is not the token, and neither is the token in the middle of a sentence: matching
        // loosely is how "no-encoder-yet" would become permanent, or a reason that merely mentions
        // being busy would stop a retry that should have happened.
        assertEquals(H264StreamProtocol.Refusal.UNKNOWN,
            H264StreamProtocol.Refusal.fromReason("no-encoder-yet, try later"));
        assertEquals(H264StreamProtocol.Refusal.UNKNOWN,
            H264StreamProtocol.Refusal.fromReason("the encoder is busy"));
    }

    @Test
    public void aTokenThisBuildCannotReadIsTransient() {
        // The safe default in the direction that costs a retry rather than a permanent downgrade:
        // an unknown word is a newer host talking, and an old client that gave up on the strength
        // of it would never find out.
        assertEquals(H264StreamProtocol.Refusal.UNKNOWN,
            H264StreamProtocol.Refusal.fromReason("resizing hold on"));
        assertEquals(H264StreamProtocol.Refusal.UNKNOWN, H264StreamProtocol.Refusal.fromReason(""));
        assertEquals(H264StreamProtocol.Refusal.UNKNOWN, H264StreamProtocol.Refusal.fromReason(null));
        assertTrue(H264StreamProtocol.Refusal.NO_ENCODER.permanent);
        assertFalse(H264StreamProtocol.Refusal.BUSY.permanent);
        assertFalse(H264StreamProtocol.Refusal.UNKNOWN.permanent);
    }

    @Test
    public void theReasonSurvivesAServerThatClosesInsteadOfTerminating() throws IOException {
        // The server writes the sentence and hangs up. An unterminated reason is the whole reason,
        // not a truncation -- reading it as one would throw away the only thing a refusal carries.
        var raw = concat(bytes('D', 'V', 'H', 'X'), "no-encoder".getBytes("US-ASCII"));
        var e = assertThrows(H264StreamProtocol.RefusedException.class,
            () -> H264StreamProtocol.readHeader(stream(raw)));
        assertEquals(H264StreamProtocol.Refusal.NO_ENCODER, e.refusal);
    }

    @Test
    public void aRefusalWithNoTerminatorAtAllStillEnds() throws IOException {
        // A reason bounded only by the sender's honesty is a read that never returns. The bound is
        // a diagnostic one -- the sentence is on its way to a log and nothing else reads it.
        var flood = new byte[H264StreamProtocol.MAX_REASON_BYTES * 4];
        java.util.Arrays.fill(flood, (byte) 'x');
        var e = assertThrows(H264StreamProtocol.RefusedException.class,
            () -> H264StreamProtocol.readHeader(stream(concat(bytes('D', 'V', 'H', 'X'), flood))));
        assertEquals(H264StreamProtocol.MAX_REASON_BYTES, e.reason.length());
    }

    @Test
    public void aRefusalIsReadWholeEvenOffADribblingSocket() throws IOException {
        // The magic and the reason are two reads on the same stream, so the same short-read hazard
        // that the header has applies to telling a refusal apart from a header at all.
        var in = dribble(refusal("no-encoder nothing to encode with"));
        var e = assertThrows(H264StreamProtocol.RefusedException.class,
            () -> H264StreamProtocol.readHeader(in));
        assertEquals(H264StreamProtocol.Refusal.NO_ENCODER, e.refusal);
        assertEquals("no-encoder nothing to encode with", e.reason);
    }

    @Test
    public void aShortReadIsNotAShortMessage() throws IOException {
        // A socket may hand over fewer bytes than asked for at any time. This one hands over one
        // byte per read, which is the same thing an ordinary socket does under load, and both the
        // header and the frames have to survive it.
        var payload = bytes(0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22, 0x33);
        var in = dribble(concat(header(800, 600), frame(payload)));
        var header = H264StreamProtocol.readHeader(in);
        assertEquals(800, header.width);
        assertEquals(600, header.height);
        assertArrayEquals(payload, H264StreamProtocol.readFrame(in));
    }

    // ---- fixtures ----

    private static InputStream stream(byte[] raw) {
        return new ByteArrayInputStream(raw);
    }

    /** A stream that never returns more than one byte from a read, however much was asked for. */
    private static InputStream dribble(byte[] raw) {
        var backing = new ByteArrayInputStream(raw);
        return new InputStream() {
            @Override
            public int read() {
                return backing.read();
            }

            @Override
            public int read(byte[] buf, int off, int len) {
                if (len == 0) return 0;
                var one = backing.read();
                if (one < 0) return -1;
                buf[off] = (byte) one;
                return 1;
            }
        };
    }

    private static byte[] header(int width, int height) {
        return new byte[]{
            'D', 'V', 'H', '2',
            (byte) (width & 0xFF), (byte) ((width >> 8) & 0xFF),
            (byte) (height & 0xFF), (byte) ((height >> 8) & 0xFF)};
    }

    /** What a server that will not serve this client writes instead of a header. */
    private static byte[] refusal(String reason) {
        var text = reason.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return concat(bytes('D', 'V', 'H', 'X'), text, new byte[]{0});
    }

    private static byte[] frameHeaderOnly(long length) {
        return new byte[]{
            (byte) (length & 0xFF), (byte) ((length >> 8) & 0xFF),
            (byte) ((length >> 16) & 0xFF), (byte) ((length >> 24) & 0xFF)};
    }

    private static byte[] frame(byte[] payload) {
        return concat(frameHeaderOnly(payload.length), payload);
    }

    private static byte[] bytes(int... values) {
        var out = new byte[values.length];
        for (var i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
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
