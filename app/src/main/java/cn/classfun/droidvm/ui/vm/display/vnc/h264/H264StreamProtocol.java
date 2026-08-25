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
 * <p><b>Silence is not a fault.</b> A screen nobody is changing produces no frames at all, for as
 * long as that lasts, so nothing here may treat a quiet socket as a dead one -- the read timeout
 * belongs to the handshake and is lifted afterwards ({@link H264SideChannel}). What this class does
 * insist on is that every read either completes or fails loudly: a stream that ends mid-length or
 * mid-frame is a truncation, not a shorter frame, and the difference between the two is a decoder
 * fed garbage versus a console that falls back to RFB.</p>
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
    /** Magic plus the two dimensions. */
    public static final int HEADER_BYTES = 8;
    /**
     * The largest frame this client will allocate for. An IDR of a desktop-sized screen is orders
     * of magnitude below this; anything above it is a stream that has lost its place.
     */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    /** The largest dimension a decoder on this class of device could plausibly be configured for. */
    public static final int MAX_DIMENSION = 16384;

    private H264StreamProtocol() {
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
     * <p>There is no "maybe this is not our server" return: the port was named by the daemon for
     * this binding, so anything else answering on it is a fault worth a message rather than a
     * silent retry.</p>
     *
     * @throws EOFException if the stream ends before the header is complete -- including the empty
     *                      stream, which is what a server refusing a second client looks like.
     * @throws IOException  if the magic or the dimensions are not ones this client can use.
     */
    @NonNull
    public static Header readHeader(@NonNull InputStream in) throws IOException {
        var head = readFully(in, HEADER_BYTES, "header");
        for (var i = 0; i < MAGIC.length; i++)
            if (head[i] != MAGIC[i])
                throw new IOException(fmt(
                    "not an H.264 side channel: magic %02x%02x%02x%02x",
                    head[0], head[1], head[2], head[3]));
        var width = u16(head, 4);
        var height = u16(head, 6);
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION)
            throw new IOException(fmt("side channel announced %dx%d", width, height));
        return new Header(width, height);
    }

    /**
     * Reads one length-prefixed frame, or null when the stream ended cleanly at a frame boundary.
     *
     * <p>Null is the ordinary end: the VM stopped, or the server dropped this client. It is
     * distinguished from every other ending by where the stream stopped, which is why the first
     * byte of the prefix is read on its own -- a stream that ends there has ended between frames,
     * and one that ends after it has ended inside one.</p>
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
        // Zero is refused rather than skipped: no NAL unit is empty, so a zero says the stream and
        // this parser disagree about where frames start, and reading past it would keep that
        // disagreement going quietly for as long as the connection lasts.
        if (length == 0) throw new IOException("side channel sent a zero-length frame");
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
