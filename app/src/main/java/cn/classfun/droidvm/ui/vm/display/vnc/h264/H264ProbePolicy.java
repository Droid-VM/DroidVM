// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.h264;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264StreamProtocol.Refusal;
import cn.classfun.droidvm.ui.vm.display.vnc.h264.H264StreamProtocol.RefusedException;

/**
 * When, and whether, the console asks for the H.264 stream again.
 *
 * <p>A probe that fails used to be the end of it: the console stayed on RFB for the rest of its
 * life, whatever the reason had been. That is right for exactly one reason and wrong for all the
 * others. A host with no encoder will not grow one, so asking again is noise. A host that was busy,
 * or that had no frame to encode yet, or that was restarting, will stop being any of those --
 * usually within seconds -- and the console that gave up is the one still paying for a software
 * encode nobody needed.</p>
 *
 * <p>So the ladder: five seconds, then ten, then fifteen and fifteen thereafter. It climbs because
 * repeated failures say the reason is not about to go away; it stops climbing because there is no
 * failure this could be that is worth waiting more than fifteen seconds to retest, and a console
 * left open for an hour should still notice the moment the encoder arrives. A stream that actually
 * came up puts the ladder back at the bottom: whatever went wrong afterwards is a new fault and
 * inherits nothing from the one before it.</p>
 *
 * <p><b>One refusal is different and it is the only one.</b> {@code no-encoder} is a fact about the
 * host, and the host does not change while the VM runs, so it ends the retries for good. Every
 * other refusal -- including one whose token this build does not recognise -- is transient, because
 * treating an unread word as permanent would let a newer host's new vocabulary silently downgrade
 * an old client forever.</p>
 *
 * <p>All of it lives here rather than in the activity so that the schedule can be read off a test
 * instead of a stopwatch: this class has no Android in it and no clock of its own.</p>
 */
public final class H264ProbePolicy {
    /**
     * The retry ladder, in milliseconds. The last rung repeats for as long as the console is open.
     */
    public static final long[] BACKOFF_MS = {5_000, 10_000, 15_000};

    /** Returned instead of a delay when nothing will be gained by asking again. */
    public static final long STOP = -1;

    private int rung;
    private boolean givenUp;

    /** Whether this console has stopped asking for good. */
    public boolean isGivenUp() {
        return givenUp;
    }

    /**
     * The stream came up. The next failure, whatever it is, starts again from the bottom rung.
     *
     * <p>Note that this does not clear {@link #isGivenUp}: nothing can be both, since a console
     * that has given up never opens a stream to succeed at.</p>
     */
    public void onLive() {
        rung = 0;
    }

    /**
     * How long to wait before probing again after a stream ended or a probe failed.
     *
     * @param cause what ended it: a {@link RefusedException} when the server said why, any other
     *              exception when it did not, and null for a deliberate close.
     * @return the delay in milliseconds, or {@link #STOP} when this console must not ask again.
     */
    public long nextDelayMs(@Nullable Exception cause) {
        if (givenUp) return STOP;
        if (refusalOf(cause).permanent) {
            givenUp = true;
            return STOP;
        }
        var delay = BACKOFF_MS[Math.min(rung, BACKOFF_MS.length - 1)];
        rung++;
        return delay;
    }

    /**
     * What the server said, reduced to the classification. Anything that is not a refusal is a
     * failure to connect, a truncation or a timeout -- none of which are the host declining, and
     * all of which are worth asking about again.
     */
    @NonNull
    public static Refusal refusalOf(@Nullable Exception cause) {
        return cause instanceof RefusedException
            ? ((RefusedException) cause).refusal : Refusal.UNKNOWN;
    }
}
