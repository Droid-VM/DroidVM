// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The rect a decoder can start on, kept across everything on this side that can come and go.
 *
 * <p>The case being pinned is the presentation console: it connects when it opens and builds its
 * decoder only once a display has been chosen, so the reset-flagged rect that starts the stream
 * routinely arrives with nowhere to go. Every bare IDR after it decodes to nothing without the
 * parameter sets it carried, and the server sends it once per client -- so a cache that forgets it
 * is a console that stays black until the geometry changes.</p>
 *
 * <p>The fixtures are the same literal bytes as {@link H264RectProtocolTest}, for the same reason
 * they are literal there.</p>
 */
public class H264SyncFrameCacheTest {
    /** SPS, PPS and an IDR behind four-byte start codes: what a joining client is sent. */
    private static final String SYNC_ANNEX_B =
        "000000016742001E" + "0000000168CE3C80" + "0000000165888421";
    /** One non-IDR slice: an ordinary later frame, which primes nothing. */
    private static final String DELTA_ANNEX_B = "00000001419A1234";

    @Test
    public void aSyncRectArrivingWithNoPipelineIsKept() {
        var cache = new H264SyncFrameCache();
        // length 24, flags 0x2 (ResetAllContexts) -- the rect that starts a stream.
        cache.rememberIfSync(hex("00000018", "00000002", SYNC_ANNEX_B), 1280, 720);
        assertArrayEquals(hex(SYNC_ANNEX_B), cache.forGeometry(1280, 720));
    }

    @Test
    public void anOrdinaryRectIsNotASyncFrame() {
        var cache = new H264SyncFrameCache();
        cache.rememberIfSync(hex("00000008", "00000000", DELTA_ANNEX_B), 1280, 720);
        assertNull("a delta carries no parameter sets", cache.forGeometry(1280, 720));
    }

    @Test
    public void theOtherResetFlagCountsToo() {
        // 0x1 is ResetContext, which is what the server sends for a plain join; 0x2 only follows a
        // geometry change. A cache that read one and not the other would miss the ordinary case.
        var cache = new H264SyncFrameCache();
        cache.rememberIfSync(hex("00000018", "00000001", SYNC_ANNEX_B), 1280, 720);
        assertNotNull(cache.forGeometry(1280, 720));
    }

    @Test
    public void aSyncFrameIsOnlyOfferedAtItsOwnGeometry() {
        // The parameter sets describe a coded size. Priming a 1920x1080 decoder with the SPS of a
        // 1280x720 stream is not a recovery, it is a decoder configured for the wrong picture.
        var cache = new H264SyncFrameCache();
        cache.rememberIfSync(hex("00000018", "00000002", SYNC_ANNEX_B), 1280, 720);
        assertNull(cache.forGeometry(1920, 1080));
    }

    @Test
    public void aGuestResizeReplacesIt() {
        var cache = new H264SyncFrameCache();
        cache.rememberIfSync(hex("00000018", "00000002", SYNC_ANNEX_B), 1280, 720);
        cache.rememberIfSync(hex("00000008", "00000002", DELTA_ANNEX_B), 1920, 1080);
        assertNull("the old geometry is gone, not kept beside the new one",
            cache.forGeometry(1280, 720));
        assertArrayEquals(hex(DELTA_ANNEX_B), cache.forGeometry(1920, 1080));
    }

    @Test
    public void aBodyThatWillNotParseIsIgnoredRatherThanThrown() {
        // There is no pipeline to bring down over it and the connection is fine -- the reader took
        // exactly the bytes the length declared. A rect kept from before must survive it.
        var cache = new H264SyncFrameCache();
        cache.rememberIfSync(hex("00000018", "00000002", SYNC_ANNEX_B), 1280, 720);
        cache.rememberIfSync(hex("00000018", "00000002", DELTA_ANNEX_B), 1280, 720);
        assertArrayEquals(hex(SYNC_ANNEX_B), cache.forGeometry(1280, 720));
        cache.rememberIfSync(hex("00"), 1280, 720);
        assertArrayEquals(hex(SYNC_ANNEX_B), cache.forGeometry(1280, 720));
    }

    @Test
    public void aRectWithNoPictureInItIsIgnored() {
        var cache = new H264SyncFrameCache();
        cache.rememberIfSync(hex("00000018", "00000002", SYNC_ANNEX_B), 0, 720);
        assertNull(cache.forGeometry(0, 720));
    }

    @Test
    public void theConnectionEndingForgetsIt() {
        // The next connection joins the stream again and is sent its own. Priming a decoder with
        // the parameter sets of a stream nobody is sending any more is worse than not priming it.
        var cache = new H264SyncFrameCache();
        cache.rememberIfSync(hex("00000018", "00000002", SYNC_ANNEX_B), 1280, 720);
        cache.clear();
        assertNull(cache.forGeometry(1280, 720));
    }

    /** The fixture bytes, one wire field per argument, as in H264RectProtocolTest. */
    private static byte[] hex(String... parts) {
        var s = String.join("", parts);
        var out = new byte[s.length() / 2];
        for (var i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
