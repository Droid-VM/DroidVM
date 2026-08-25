// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The wire format of the VNC H.264 side channel, and the only part of the decoder path that can be
 * tested without a device.
 *
 * <p>The channel is a plain TCP stream beside the RFB one. On connect the server sends eight bytes
 * -- the magic {@code DVH2}, then the encoded width and height as little-endian {@code u16} -- and
 * after that a stream of frames, each a little-endian {@code u32} byte count followed by that many
 * bytes of Annex-B NAL units. Parameter sets ride inline ahead of the IDRs that need them, so there
 * is nothing to negotiate and nothing to remember between connections: the server produces a sync
 * frame for whoever just arrived.</p>
 *
 * <p><b>Silence is a fault now, and that is what the heartbeat bought.</b> A screen nobody is
 * changing produces no frames, so a quiet socket used to be indistinguishable from a host that had
 * died with the picture frozen on it -- and the console froze with it, because the RFB updates it
 * had stopped asking for were still suppressed for a decoder that was never going to be fed again.
 * So the host now writes a frame of length zero every three seconds it has had nothing else to say.
 * It is not a picture and this parser does not return it as one; it exists so that both ends have
 * something to time out against, which is what lets {@link H264SideChannel} put a read timeout back
 * on the stream without a still desktop looking like a dead one.</p>
 *
 * <p>What this class insists on besides is that every read either completes or fails loudly: a
 * stream that ends mid-length or mid-frame is a truncation, not a shorter frame, and the difference
 * between the two is a decoder fed garbage versus a console that falls back to RFB.</p>
 *
 * <p><b>A refusal is an answer, not a stranger on the port.</b> A server that will not serve this
 * client writes {@code DVHX} and a NUL-terminated sentence instead of a header. The sentence starts
 * with a machine token -- and only the token is read as meaning, because the rest of it exists for
 * a human reading a log. Which token it is decides whether the console ever asks again: a host with
 * no encoder will not grow one, and everything else is a reason to come back later.</p>
 *
 * <p>Both length guards exist because the far end is a length prefix the parser cannot check
 * against anything else. A count is unsigned on the wire and read into a {@code long} so that the
 * high bit is a large number rather than a negative one, and it is refused above
 * {@link #MAX_FRAME_BYTES} -- past which the only readings are a desynchronised stream or a
 * malicious one, and both are better answered by ending the connection than by allocating what the
 * number asked for.</p>
 */
public final class H264StreamProtocol {
    /** The four bytes a conforming server opens with. */
    static final byte[] MAGIC = {'D', 'V', 'H', '2'};
    /** The four bytes a server that will not serve this client opens with instead. */
    static final byte[] MAGIC_REFUSED = {'D', 'V', 'H', 'X'};
    /** Just the magic, which is as far as a reader can get before it knows which one it has. */
    public static final int MAGIC_BYTES = 4;
    /** Magic plus the two dimensions. */
    public static final int HEADER_BYTES = 8;
    /**
     * The largest frame this client will allocate for. An IDR of a desktop-sized screen is orders
     * of magnitude below this; anything above it is a stream that has lost its place.
     */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    /** The largest dimension a decoder on this class of device could plausibly be configured for. */
    public static final int MAX_DIMENSION = 16384;
    /**
     * How much of a refusal sentence is read before the rest is thrown away. It is a diagnostic
     * string on its way to a log, so the only thing that has to be bounded about it is that a
     * server that forgets the NUL cannot make this read forever.
     */
    public static final int MAX_REASON_BYTES = 512;
    /**
     * What {@link #readFrame} returns for a heartbeat: a frame with no picture in it.
     *
     * <p>Zero length is the whole signal, so there is nothing to allocate and nothing to hand a
     * decoder. Callers tell it apart by its length, not by its identity, because a heartbeat that
     * arrived in two reads is still a heartbeat.</p>
     */
    public static final byte[] HEARTBEAT = new byte[0];

    /** The machine token a host with no usable encoder opens its refusal with. */
    public static final String TOKEN_NO_ENCODER = "no-encoder";
    /** The machine token a host that already has a client opens its refusal with. */
    public static final String TOKEN_BUSY = "busy";

    private H264StreamProtocol() {
    }

    /**
     * Why a server turned this client away, reduced to the one thing the console has to decide:
     * whether asking again could ever produce a different answer.
     */
    public enum Refusal {
        /** No encoder on this host. Nothing will change that while the VM runs. */
        NO_ENCODER(true),
        /** Somebody else holds the single stream slot. Theirs to give up. */
        BUSY(false),
        /**
         * A token this build does not know. Transient by default and deliberately so: an unknown
         * word is a newer host saying something, and refusing to ever ask again on the strength of
         * a word we cannot read would turn a vocabulary gap into a permanent downgrade.
         */
        UNKNOWN(false);

        /** Whether retrying is pointless for as long as this console is open. */
        public final boolean permanent;

        Refusal(boolean permanent) {
            this.permanent = permanent;
        }

        /** Classifies a refusal sentence by the token it starts with. */
        @NonNull
        public static Refusal fromReason(@Nullable String reason) {
            if (reason == null) return UNKNOWN;
            var space = reason.indexOf(' ');
            var token = space < 0 ? reason : reason.substring(0, space);
            if (TOKEN_NO_ENCODER.equals(token)) return NO_ENCODER;
            if (TOKEN_BUSY.equals(token)) return BUSY;
            return UNKNOWN;
        }
    }

    /**
     * The server answered, and the answer was no.
     *
     * <p>An {@link IOException} because that is what every caller already treats as the end of the
     * stream, and a distinct one because this ending -- unlike a truncation or a closed port --
     * carries a reason the console is expected to act on rather than merely log.</p>
     */
    public static final class RefusedException extends IOException {
        /** What the leading token said, or {@link Refusal#UNKNOWN}. */
        @NonNull
        public final Refusal refusal;
        /** The whole sentence, token included, for the log and for nothing else. */
        @NonNull
        public final String reason;

        RefusedException(@NonNull Refusal refusal, @NonNull String reason) {
            super(fmt("the H.264 side channel refused this client: %s", reason));
            this.refusal = refusal;
            this.reason = reason;
        }
    }

    /** The stream's geometry, as the server announced it before the first frame. */
    public static final class Header {
        public final int width;
        public final int height;

        Header(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Reads the eight-byte header, or throws.
     *
     * <p>The magic is read on its own before anything else, because the two things a conforming
     * server can send differ in what follows it: a header is a fixed eight bytes and a refusal is a
     * sentence of unknown length. Reading eight and then looking would swallow the first half of a
     * refusal's reason and leave the parser standing in the middle of it.</p>
     *
     * <p>There is no "maybe this is not our server" return: the port was named by the daemon for
     * this binding, so anything else answering on it is a fault worth a message rather than a
     * silent retry.</p>
     *
     * @throws RefusedException if the server answered with {@code DVHX} and a reason.
     * @throws EOFException     if the stream ends before the header is complete -- including the
     *                          empty stream, which is what a server that accepted and then thought
     *                          better of it looks like.
     * @throws IOException      if the magic or the dimensions are not ones this client can use.
     */
    @NonNull
    public static Header readHeader(@NonNull InputStream in) throws IOException {
        var magic = readFully(in, MAGIC_BYTES, "magic");
        if (matches(magic, MAGIC_REFUSED)) {
            var reason = readReason(in);
            throw new RefusedException(Refusal.fromReason(reason), reason);
        }
        if (!matches(magic, MAGIC))
            throw new IOException(fmt(
                "not an H.264 side channel: magic %02x%02x%02x%02x",
                magic[0], magic[1], magic[2], magic[3]));
        var dims = readFully(in, HEADER_BYTES - MAGIC_BYTES, "header");
        var width = u16(dims, 0);
        var height = u16(dims, 2);
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION)
            throw new IOException(fmt("side channel announced %dx%d", width, height));
        return new Header(width, height);
    }

    /**
     * Reads a refusal's NUL-terminated sentence.
     *
     * <p>The end of the stream ends it as surely as the NUL does: the server closes immediately
     * after writing, so a reason that arrives without its terminator is still the whole reason, and
     * turning that into a truncation error would throw away the one thing the refusal was for.</p>
     */
    @NonNull
    private static String readReason(@NonNull InputStream in) throws IOException {
        var out = new StringBuilder();
        while (out.length() < MAX_REASON_BYTES) {
            var b = in.read();
            if (b <= 0) break;
            out.append((char) (b & 0xFF));
        }
        return out.toString();
    }

    private static boolean matches(@NonNull byte[] read, @NonNull byte[] expected) {
        for (var i = 0; i < expected.length; i++)
            if (read[i] != expected[i]) return false;
        return true;
    }

    /**
     * Reads one length-prefixed frame, or null when the stream ended cleanly at a frame boundary.
     *
     * <p>Null is the ordinary end: the VM stopped, or the server dropped this client. It is
     * distinguished from every other ending by where the stream stopped, which is why the first
     * byte of the prefix is read on its own -- a stream that ends there has ended between frames,
     * and one that ends after it has ended inside one.</p>
     *
     * <p><b>A zero length is a heartbeat and is returned as {@link #HEARTBEAT}</b>, an empty array
     * the caller skips. This used to be an error, on the argument that no NAL unit is empty and so
     * a zero could only mean the parser and the stream disagreed about where frames began. The
     * argument was sound and the conclusion is now wrong: the host writes exactly this, on purpose,
     * every three seconds it has nothing else to send, and it is the only thing that makes a read
     * timeout on an idle screen mean what it says. The desynchronisation the old guard was aimed at
     * is still caught, one prefix later, by {@link #MAX_FRAME_BYTES}.</p>
     *
     * @throws EOFException if the length prefix or the body is cut short.
     * @throws IOException  if the length is one no frame can have.
     */
    @Nullable
    public static byte[] readFrame(@NonNull InputStream in) throws IOException {
        var first = in.read();
        if (first < 0) return null;
        var rest = readFully(in, 3, "frame length");
        var length = (first & 0xFFL)
            | ((rest[0] & 0xFFL) << 8)
            | ((rest[1] & 0xFFL) << 16)
            | ((rest[2] & 0xFFL) << 24);
        if (length == 0) return HEARTBEAT;
        if (length > MAX_FRAME_BYTES)
            throw new IOException(fmt(
                "side channel frame of %d bytes exceeds the %d-byte guard",
                length, MAX_FRAME_BYTES));
        return readFully(in, (int) length, "frame body");
    }

    private static int u16(@NonNull byte[] buf, int at) {
        return (buf[at] & 0xFF) | ((buf[at + 1] & 0xFF) << 8);
    }

    /**
     * Exactly [count] bytes or an exception. A single {@code read} is allowed to return fewer bytes
     * than asked for on any socket, so treating one short read as the end of the message is a bug
     * that only appears under load -- which is where it costs the most.
     */
    @NonNull
    private static byte[] readFully(@NonNull InputStream in, int count, @NonNull String what)
        throws IOException {
        var buf = new byte[count];
        var off = 0;
        while (off < count) {
            var read = in.read(buf, off, count - off);
            if (read < 0)
                throw new EOFException(fmt(
                    "truncated %s: %d of %d bytes", what, off, count));
            off += read;
        }
        return buf;
    }
}
