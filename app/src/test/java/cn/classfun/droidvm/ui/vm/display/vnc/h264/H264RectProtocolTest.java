package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * The seam, in bytes.
 *
 * <p>Every fixture here is written from {@code plans/H264_SINGLE_PORT.md} section 1 rather than
 * from the parser, and is meant to be diffed literally against the bytes the server-side test
 * asserts. That is the whole point of them: two agents implementing one contract from one document
 * is exactly the arrangement in which each can be internally consistent and the pair still not
 * meet, and the only check that catches it is the same literal bytes appearing on both sides.</p>
 *
 * <p>The hex is spelt out rather than built, for the same reason. A fixture assembled by a helper
 * that shares its idea of byte order with the code under test asserts that the code agrees with
 * itself.</p>
 */
public class H264RectProtocolTest {
    /**
     * Twenty-four bytes of Annex-B: three NAL units behind four-byte start codes, typed 7 (SPS),
     * 8 (PPS) and 5 (IDR). Synthetic past the type byte -- what is being pinned is the framing
     * around it, not the coded picture, and a decoder never sees this.
     */
    private static final String SYNC_ANNEX_B =
        "000000016742001E" + "0000000168CE3C80" + "0000000165888421";

    /** Eight bytes of Annex-B: one NAL unit typed 1, a non-IDR slice -- an ordinary later frame. */
    private static final String DELTA_ANNEX_B = "00000001419A1234";

    // ---- Encoding 50: u32 BE length, u32 BE flags, then that many bytes of Annex-B. ----

    @Test
    public void aSyncRectCarriesItsLengthItsResetFlagAndItsPayload() {
        // length 0x00000018 = 24, flags 0x00000002 = ResetAllContexts, then the 24 bytes.
        var body = hex("00000018", "00000002", SYNC_ANNEX_B);
        assertEquals(H264RectProtocol.RECT_HEADER_BYTES + 24, body.length);

        var rect = parse(body);
        assertEquals(H264RectProtocol.FLAG_RESET_ALL_CONTEXTS, rect.flags);
        assertTrue(rect.resetsDecoder());
        assertArrayEquals(hex(SYNC_ANNEX_B), rect.annexB);
    }

    @Test
    public void anOrdinaryRectResetsNothing() {
        var body = hex("00000008", "00000000", DELTA_ANNEX_B);
        var rect = parse(body);
        assertEquals(0, rect.flags);
        assertFalse(rect.resetsDecoder());
        assertArrayEquals(hex(DELTA_ANNEX_B), rect.annexB);
    }

    @Test
    public void theOtherResetFlagIsAlsoAReset() {
        // 0x1 is ResetContext: a viewer keeping one context per rectangle has two different things
        // to throw away, and this console has one codec, so both flags reach it as the same order.
        var body = hex("00000008", "00000001", DELTA_ANNEX_B);
        var rect = parse(body);
        assertEquals(H264RectProtocol.FLAG_RESET_CONTEXT, rect.flags);
        assertTrue(rect.resetsDecoder());
    }

    @Test
    public void theLengthIsBigEndianAndNotTheOtherWay() {
        // The one byte that separates 24 from 402653184. Little-endian here would read the flags
        // word as the length and walk off the end of every frame after the first.
        var body = hex("00000018", "00000000", SYNC_ANNEX_B);
        assertEquals(24, parse(body).annexB.length);
        var swapped = hex("18000000", "00000000", SYNC_ANNEX_B);
        assertThrows(IOException.class, () -> H264RectProtocol.parseStreamRect(swapped));
    }

    @Test
    public void aLengthThatDisagreesWithTheBytesIsRefused() {
        // The reader that pulled these off the socket read the same length to know how many to
        // ask for, so this is the two readings having diverged. Refusing is what keeps that
        // duplication from being a place the seam can quietly come apart.
        var short_ = hex("00000018", "00000000", DELTA_ANNEX_B);
        assertThrows(IOException.class, () -> H264RectProtocol.parseStreamRect(short_));
        var long_ = hex("00000004", "00000000", DELTA_ANNEX_B);
        assertThrows(IOException.class, () -> H264RectProtocol.parseStreamRect(long_));
    }

    @Test
    public void aBodyTooShortForItsOwnHeaderIsRefused() {
        assertThrows(IOException.class, () -> H264RectProtocol.parseStreamRect(hex("00000000")));
        assertThrows(IOException.class, () -> H264RectProtocol.parseStreamRect(new byte[0]));
        assertThrows(IOException.class, () -> H264RectProtocol.parseStreamRect(null));
    }

    @Test
    public void aLengthPastTheGuardIsRefusedWithoutAllocating() {
        // 0x7FFFFFFF. Read into a long, so the high bit is a large number and not a negative one --
        // a signed read would make this a negative length and sail past the guard.
        var body = hex("7FFFFFFF", "00000000", DELTA_ANNEX_B);
        assertThrows(IOException.class, () -> H264RectProtocol.parseStreamRect(body));
        var high = hex("FFFFFFFF", "00000000", DELTA_ANNEX_B);
        assertThrows(IOException.class, () -> H264RectProtocol.parseStreamRect(high));
    }

    @Test
    public void anEmptyPayloadIsAWellFormedRectWithNothingInIt() {
        var rect = parse(hex("00000000" + "00000002"));
        assertEquals(0, rect.annexB.length);
        assertTrue(rect.resetsDecoder());
    }

    // ---- 0x44564831: version, kind, value, reserved. Four bytes, always. ----

    @Test
    public void theCapabilitiesRectSaysWhetherThereIsAnEncoder() {
        // 01 00 00 00 -- v1, kind 0 (capabilities), value 0 (h264 stream available), reserved 0.
        var available = H264RectProtocol.parseDvhRect(hex("01000000"));
        assertNotNull(available);
        assertEquals(H264RectProtocol.DVH_VERSION, available.version);
        assertTrue(available.isCapabilities());
        assertFalse(available.isHeartbeat());
        assertEquals(H264RectProtocol.CAPS_AVAILABLE, available.value);

        // 01 00 01 00 -- value 1: no encoder on this host, permanent, stop waiting.
        var none = H264RectProtocol.parseDvhRect(hex("01000100"));
        assertNotNull(none);
        assertTrue(none.isCapabilities());
        assertEquals(H264RectProtocol.CAPS_NO_ENCODER, none.value);

        // 01 00 02 00 -- value 2: warming. Asked for, not producing yet.
        var warming = H264RectProtocol.parseDvhRect(hex("01000200"));
        assertNotNull(warming);
        assertTrue(warming.isCapabilities());
        assertEquals(H264RectProtocol.CAPS_WARMING, warming.value);
    }

    @Test
    public void theHeartbeatRectIsKindOneAndCarriesNothing() {
        // 01 01 00 00 -- v1, kind 1 (heartbeat), value 0, reserved 0.
        var beat = H264RectProtocol.parseDvhRect(hex("01010000"));
        assertNotNull(beat);
        assertTrue(beat.isHeartbeat());
        assertFalse(beat.isCapabilities());
        assertEquals(0, beat.value);
    }

    @Test
    public void anUnknownKindParsesAndIsNeitherOfTheTwo() {
        // Section 1: a client must ignore unknown kinds. Ignoring is the caller's job, so what
        // this side owes is a rect that answers "no" to both questions rather than one that
        // throws.
        var odd = H264RectProtocol.parseDvhRect(hex("01070000"));
        assertNotNull(odd);
        assertEquals(7, odd.kind);
        assertFalse(odd.isCapabilities());
        assertFalse(odd.isHeartbeat());
    }

    @Test
    public void anUnknownVersionIsIgnoredRatherThanRefused() {
        // A newer host saying something. Dropping the connection over it would turn a vocabulary
        // gap into an outage, which is the failure this whole encoding exists to avoid.
        assertNull(H264RectProtocol.parseDvhRect(hex("02000000")));
        assertNull(H264RectProtocol.parseDvhRect(hex("00000000")));
    }

    @Test
    public void bytesPastTheFourthAreIgnored() {
        // v1 has none. A later version's extra ones must not make a v1 field unreadable.
        var beat = H264RectProtocol.parseDvhRect(hex("01010000DEADBEEF"));
        assertNotNull(beat);
        assertTrue(beat.isHeartbeat());
    }

    @Test
    public void aShortPayloadIsNotALongerMessage() {
        assertNull(H264RectProtocol.parseDvhRect(hex("010100")));
        assertNull(H264RectProtocol.parseDvhRect(new byte[0]));
        assertNull(H264RectProtocol.parseDvhRect(null));
    }

    @Test
    public void theEncodingNumbersAreTheOnesTheServerWasToldToUse() {
        assertEquals(50, H264RectProtocol.ENCODING_H264);
        // "DVH1" in ASCII, which is how it reads in a packet dump on either side.
        assertEquals(0x44564831, H264RectProtocol.ENCODING_DVH1);
        assertArrayEquals(new byte[]{'D', 'V', 'H', '1'}, hex("44564831"));
    }

    private static H264RectProtocol.StreamRect parse(byte[] body) {
        try {
            return H264RectProtocol.parseStreamRect(body);
        } catch (IOException e) {
            throw new AssertionError("fixture should have parsed", e);
        }
    }

    /**
     * The fixture bytes. Each wire field is its own argument rather than a '+' away from the next,
     * so the header words stay visible as the separate things they are.
     */
    private static byte[] hex(String... parts) {
        var s = String.join("", parts);
        var out = new byte[s.length() / 2];
        for (var i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
