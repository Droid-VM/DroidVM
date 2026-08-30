// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import androidx.annotation.NonNull;

/**
 * Whether this console is decoding, waiting for something to decode, or on the pixel path -- and
 * what has to happen to move it between the three.
 *
 * <p>The rules are {@code plans/H264_SINGLE_PORT.md} section 1's client half, and they are here
 * rather than in the activity so that they can be read off a test instead of a stopwatch: this
 * class has no Android in it and no clock of its own. Every method takes the current time; the
 * activity's only job is to say what time it is and to do what {@link #tick} returns.</p>
 *
 * <p><b>Three things end the waiting, and only one of them is a message.</b> A capabilities rect
 * saying {@link H264RectProtocol#CAPS_NO_ENCODER} is a fact about the host, and the host does not
 * grow an encoder while the VM runs, so it ends the question for good. Silence is the other two.
 * Five seconds with no capabilities rect at all means the server is not one that knows this
 * pseudo-encoding -- an old crosvm, or a still-running VM started before the change -- and the
 * console simply stays on pixels, which is what that server is already serving it. Ten seconds
 * with neither a frame nor a heartbeat, while decoding, means the stream is dead however alive the
 * connection looks, because the heartbeat exists precisely so that a still screen and a dead host
 * stop looking alike.</p>
 *
 * <p><b>The silence verdict is revocable and the refusal is not.</b> Guessing from silence is a
 * guess: a capabilities rect, a frame or a heartbeat arriving afterwards is direct evidence and
 * takes the console back off the pixel path. A host that answered "no encoder" said so, and
 * nothing short of a new connection reopens it. Keeping those two apart is also what keeps the
 * status line honest -- an ordinary VNC screen must not tell every user that H.264 is unavailable
 * merely because nobody ever offered it one.</p>
 */
public final class H264ProbePolicy {
    /**
     * How long after connecting a capabilities rect may take before its absence is the answer.
     *
     * <p>The server sends it as the first answer to the first request, so anything that is going to
     * arrive arrives in one round trip on a loopback socket. The rest of this is slack for a VM
     * whose first framebuffer is still being composed.</p>
     */
    public static final long CAPS_GRACE_MS = 5_000;
    /**
     * How long a decoding console may hear nothing before the stream is declared dead.
     *
     * <p>The host beats every three seconds it has nothing else to send, so this is three intervals
     * plus change: long enough that a late beat under load is not a funeral, short enough that a
     * frozen console is measured in seconds.</p>
     */
    public static final long SILENCE_MS = 10_000;
    /**
     * How many times a dead stream is answered by reconnecting before it is answered by giving up.
     *
     * <p>One. A reconnect fixes the case this is for -- a connection or a broker that wedged with
     * the picture frozen on it -- and a second dead stream says the fault is not the sort that a
     * reconnect fixes. Retrying past that is a console that spends its life reconnecting instead of
     * showing the screen the pixel path would have shown it all along.</p>
     */
    public static final int DEAD_STREAM_RECONNECTS = 1;

    /** What the console should be showing, and therefore what its views should be doing. */
    public enum Mode {
        /** Pixels for now; a stream may yet arrive. Nothing to tell the user. */
        WAITING,
        /** The decoder is what paints this console. */
        DECODING,
        /** Pixels, and this console is not expecting to leave them. */
        PIXELS
    }

    /** What {@link #tick} asks the console to do, beyond whatever {@link #mode} now says. */
    public enum Order {
        NOTHING,
        /** The stream is dead. Drop the RFB session and open another; enrolment starts there. */
        RECONNECT
    }

    private Mode mode = Mode.WAITING;
    private boolean connected;
    private long connectedAtMs;
    /** When a frame or a heartbeat last arrived, and what the ten seconds are measured from. */
    private long lastSignalMs;
    private boolean sawCaps;
    /** The five-second verdict. A guess from silence, and so revocable by any later evidence. */
    private boolean assumedNoCaps;
    /** The host answered {@link H264RectProtocol#CAPS_NO_ENCODER}. Never revoked. */
    private boolean saidNoEncoder;
    /** This device could not stand up a decoder. Never revoked, and not about the host at all. */
    private boolean decoderUnsupported;
    private int deadStreams;
    private boolean deadStreamsExhausted;

    /** An RFB session came up. The five-second clock starts here. */
    public synchronized void onConnected(long nowMs) {
        connected = true;
        connectedAtMs = nowMs;
        lastSignalMs = nowMs;
        sawCaps = false;
        assumedNoCaps = false;
        // deadStreams deliberately survives: the reconnect that follows a dead stream is this
        // policy's own doing, and a counter reset by it would ask for reconnects forever.
        mode = isPermanent() ? Mode.PIXELS : Mode.WAITING;
    }

    /** The RFB session ended. Nothing is decided until another one comes up. */
    public synchronized void onDisconnected() {
        connected = false;
        if (mode == Mode.DECODING) mode = Mode.WAITING;
    }

    /**
     * A capabilities rect arrived. [value] is the section 1 {@code value} byte, whatever it holds.
     */
    public synchronized void onCapsRect(int value, long nowMs) {
        sawCaps = true;
        assumedNoCaps = false;
        if (value == H264RectProtocol.CAPS_NO_ENCODER) {
            saidNoEncoder = true;
            mode = Mode.PIXELS;
            return;
        }
        if (isPermanent()) return;
        // CAPS_AVAILABLE, CAPS_WARMING, and anything this build cannot read. An unread value is
        // treated as warming rather than as a refusal, for the reason every unknown token is: a
        // newer host's new vocabulary must not permanently downgrade an old client.
        if (value == H264RectProtocol.CAPS_AVAILABLE) enterDecoding(nowMs);
        else mode = Mode.WAITING;
    }

    /** A frame arrived. The strongest evidence there is, and it outranks any guess from silence. */
    public synchronized void onStreamRect(long nowMs) {
        lastSignalMs = nowMs;
        assumedNoCaps = false;
        if (!isPermanent()) enterDecoding(nowMs);
    }

    /**
     * A heartbeat arrived.
     *
     * <p>Liveness, and not a reason to start decoding: a heartbeat is exactly what a still screen
     * looks like, and there is nothing in one to put on screen. It does revoke a guess made from
     * silence, because a server that beats is a server that knew about the pseudo-encoding.</p>
     */
    public synchronized void onHeartbeat(long nowMs) {
        lastSignalMs = nowMs;
        assumedNoCaps = false;
    }

    /**
     * This device could not stand up an H.264 decoder at all.
     *
     * <p>Not a fact about the host, and the only one of the permanent verdicts the console has to
     * act on beyond changing what it shows: a client that has asked for encoding 50 is served no
     * pixels, so one that cannot decode has to stop asking before it can have a picture again.</p>
     */
    public synchronized void onDecoderUnsupported() {
        decoderUnsupported = true;
        mode = Mode.PIXELS;
    }

    /**
     * Advances the two silence clocks.
     *
     * @return what the console has to do about it. The mode may have changed either way.
     */
    @NonNull
    public synchronized Order tick(long nowMs) {
        if (!connected) return Order.NOTHING;
        if (!sawCaps && !assumedNoCaps && !isPermanent()
            && nowMs - connectedAtMs >= CAPS_GRACE_MS) {
            assumedNoCaps = true;
            mode = Mode.PIXELS;
        }
        if (mode == Mode.DECODING && nowMs - lastSignalMs >= SILENCE_MS) {
            // Rearmed whichever branch is taken, so that a console that gave up does not re-report
            // the same dead stream on every tick for the rest of its life.
            lastSignalMs = nowMs;
            deadStreams++;
            if (deadStreams > DEAD_STREAM_RECONNECTS) {
                deadStreamsExhausted = true;
                mode = Mode.PIXELS;
                return Order.NOTHING;
            }
            mode = Mode.WAITING;
            return Order.RECONNECT;
        }
        return Order.NOTHING;
    }

    @NonNull
    public synchronized Mode mode() {
        return mode;
    }

    /** Whether nothing that can still happen on this console would put it back on the decoder. */
    public synchronized boolean isPermanent() {
        return saidNoEncoder || decoderUnsupported || deadStreamsExhausted;
    }

    /**
     * Whether the host itself answered "no encoder".
     *
     * <p>Told apart from every other way of ending up on pixels because it is the only one worth
     * saying out loud: it is the case where the console can never do better and the user might
     * otherwise wonder why. Silence is not this -- an ordinary VNC server has never claimed to
     * offer a stream, and telling its user that H.264 is unavailable would be noise.</p>
     */
    public synchronized boolean saidNoEncoder() {
        return saidNoEncoder;
    }

    /** Whether this device turned out to have no decoder, which is what stops the advertisement. */
    public synchronized boolean isDecoderUnsupported() {
        return decoderUnsupported;
    }

    private void enterDecoding(long nowMs) {
        // The ten seconds are measured from when frames started being expected, not from the last
        // thing that happened to arrive. Without this a stream that warmed for a minute would be
        // declared dead on the tick after it was finally announced.
        if (mode != Mode.DECODING) lastSignalMs = nowMs;
        mode = Mode.DECODING;
    }
}
