// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;

/**
 * The two rect bodies the H.264 console reads off the ordinary RFB connection, and the only part of
 * the decoder path that can be tested without a device.
 *
 * <p>Both layouts are pinned by {@code plans/H264_SINGLE_PORT.md} section 1 and are implemented
 * here as written. Neither is negotiated, neither is derived from anything, and this class is the
 * one place either is read -- the JNI layer hands up whole rect bodies rather than parsed ones
 * precisely so that a unit test can feed this the same literal bytes the server-side test
 * asserts.</p>
 *
 * <p><b>Encoding 50</b> ("Open H.264", rfbproto.rst): {@code u32} big-endian length, {@code u32}
 * big-endian flags, then that many bytes of Annex-B NAL units. The flags say whether the decoder's
 * context is to be thrown away first, which is how a resize arrives: the picture behind a
 * reset-flagged rect is a sync frame at a geometry the previous one did not have.</p>
 *
 * <p><b>Encoding 0x44564831</b> ("DVH1"): four bytes, always, on a rect that is always 0x0 at 0,0 --
 * version, kind, value, reserved. It exists because RFB negotiation can say "I understand encoding
 * 50" and cannot say either of the two things a client actually has to know: whether there is an
 * encoder behind the server at all, and -- on a screen nobody is changing, where no frames is the
 * correct amount of frames -- whether the connection is still alive. Bytes past the fourth and
 * kinds this build does not know are ignored rather than refused, because the alternative is a
 * newer host's new vocabulary dropping an old client's connection.</p>
 *
 * <p>Both parsers refuse rather than guess when the bytes and the lengths disagree. The length in
 * an encoding-50 body is read twice -- once in C, to know how much to pull off the socket, and once
 * here -- and the check that the two readings agreed is what keeps that duplication from being a
 * place the seam can quietly come apart.</p>
 */
public final class H264RectProtocol {
    /** rfbproto.rst's "Open H.264" encoding number. */
    public static final int ENCODING_H264 = 50;
    /** "DVH1" in ASCII: DroidVM's pseudo-encoding, in the unassigned vendor-style positive space. */
    public static final int ENCODING_DVH1 = 0x44564831;

    /** u32 BE length + u32 BE flags, ahead of the Annex-B payload. */
    public static final int RECT_HEADER_BYTES = 8;
    /** Throw away the decoder context this rect's geometry names, then decode. */
    public static final int FLAG_RESET_CONTEXT = 0x1;
    /** Throw away every decoder context, then decode. Never set together with the above. */
    public static final int FLAG_RESET_ALL_CONTEXTS = 0x2;
    /**
     * The largest payload this client will allocate for. An IDR of a desktop-sized screen is orders
     * of magnitude below it; above it the only readings are a stream that has lost its place or a
     * malicious one, and both are better answered by ending the connection than by allocating what
     * the number asked for.
     */
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    /** The whole of a DVH1 rect: version, kind, value, reserved. */
    public static final int DVH_PAYLOAD_BYTES = 4;
    /** The only version this build reads. Anything else is ignored, not refused. */
    public static final int DVH_VERSION = 1;
    /** {@code kind}: what the host can do. */
    public static final int KIND_CAPABILITIES = 0;
    /** {@code kind}: the stream is quiet, not dead. */
    public static final int KIND_HEARTBEAT = 1;
    /** {@code value} of a capabilities rect: an encoder is up, or is expected to come up. */
    public static final int CAPS_AVAILABLE = 0;
    /** {@code value}: no encoder on this host. Permanent -- stop waiting for one. */
    public static final int CAPS_NO_ENCODER = 1;
    /** {@code value}: asked for, not producing yet. Wait; another caps rect will say when. */
    public static final int CAPS_WARMING = 2;

    private H264RectProtocol() {
    }

    /** One encoding-50 rect: the flags it carried and the coded bytes behind them. */
    public static final class StreamRect {
        public final int flags;
        @NonNull
        public final byte[] annexB;

        StreamRect(int flags, @NonNull byte[] annexB) {
            this.flags = flags;
            this.annexB = annexB;
        }

        /**
         * Whether the decoder must be put back to nothing before these bytes go in.
         *
         * <p>The two flags are separate on the wire because a viewer that keeps one decoder context
         * per rectangle has two different things to throw away. This console has exactly one
         * decoder, so they are the same instruction to it, and reading them as alternatives is what
         * the server does too -- it never sets both.</p>
         */
        public boolean resetsDecoder() {
            return (flags & (FLAG_RESET_CONTEXT | FLAG_RESET_ALL_CONTEXTS)) != 0;
        }
    }

    /**
     * Parses one encoding-50 rect body, header included.
     *
     * @throws IOException when the body cannot be read as one -- too short for its own header, a
     *                     length past the guard, or a length that disagrees with the bytes present.
     *                     Every one of those means the stream and the parser no longer agree about
     *                     where the next rect begins, which the caller ends the connection over.
     */
    @NonNull
    public static StreamRect parseStreamRect(@Nullable byte[] rect) throws IOException {
        if (rect == null || rect.length < RECT_HEADER_BYTES)
            throw new IOException(fmt("h264 rect of %d bytes has no room for its header",
                rect == null ? 0 : rect.length));
        var length = u32(rect, 0);
        var flags = u32(rect, 4);
        if (length > MAX_PAYLOAD_BYTES)
            throw new IOException(fmt(
                "h264 rect declares %d bytes, past the %d-byte guard", length, MAX_PAYLOAD_BYTES));
        // The reader that pulled these bytes off the socket read the same length to know how many
        // to ask for, so a disagreement here is the two readings having diverged rather than a
        // short frame -- and a decoder fed the difference produces rubbish rather than an error.
        var carried = rect.length - RECT_HEADER_BYTES;
        if (length != carried)
            throw new IOException(fmt(
                "h264 rect declares %d bytes and carries %d", length, carried));
        return new StreamRect((int) flags,
            Arrays.copyOfRange(rect, RECT_HEADER_BYTES, rect.length));
    }

    /** One DVH1 rect, as far as this build reads it. */
    public static final class DvhRect {
        public final int version;
        public final int kind;
        public final int value;

        DvhRect(int version, int kind, int value) {
            this.version = version;
            this.kind = kind;
            this.value = value;
        }

        public boolean isCapabilities() {
            return kind == KIND_CAPABILITIES;
        }

        public boolean isHeartbeat() {
            return kind == KIND_HEARTBEAT;
        }
    }

    /**
     * Parses one DVH1 rect payload, or returns null for one this build cannot read.
     *
     * <p>Null rather than an exception, and that is the whole point of the encoding: an unknown
     * version is a newer host saying something, and a client that dropped the connection over it
     * would turn a vocabulary gap into an outage. Bytes past the fourth are ignored for the same
     * reason -- v1 has none, and a later version's extra ones must not make a v1 field unreadable.
     * Short is different: fewer than four bytes is not a longer message, it is a broken one.</p>
     */
    @Nullable
    public static DvhRect parseDvhRect(@Nullable byte[] payload) {
        if (payload == null || payload.length < DVH_PAYLOAD_BYTES) return null;
        var version = payload[0] & 0xFF;
        if (version != DVH_VERSION) return null;
        return new DvhRect(version, payload[1] & 0xFF, payload[2] & 0xFF);
    }

    /** Big-endian, into a long, so that the high bit is a large number and not a negative one. */
    private static long u32(@NonNull byte[] buf, int at) {
        return ((buf[at] & 0xFFL) << 24)
            | ((buf[at + 1] & 0xFFL) << 16)
            | ((buf[at + 2] & 0xFFL) << 8)
            | (buf[at + 3] & 0xFFL);
    }
}
