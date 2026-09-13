// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.peripheral;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The display hold's state machine -- what the platform is actually asked to do, and when.
 *
 * <p>The point of the {@link ScreenWakeLock.Handle} seam is this file: a JVM unit test has a
 * {@code PowerManager} that returns nothing, so the real lock cannot be exercised, while the part
 * that can be wrong without anyone noticing is the order. A second acquire that the platform
 * counts is a phone that never sleeps again; a release that never happens is the same thing.
 * Both are invisible on a bench and expensive on a user's phone, so they are asserted here rather
 * than looked for in {@code dumpsys power}.</p>
 */
public final class ScreenWakeLockTest {
    /** Records what the platform was told, in order. */
    private static final class Recorder implements ScreenWakeLock.Handle {
        final List<String> calls = new ArrayList<>();
        RuntimeException failWith;

        @Override
        public void acquire() {
            calls.add("acquire");
            if (failWith != null) throw failWith;
        }

        @Override
        public void release() {
            calls.add("release");
            if (failWith != null) throw failWith;
        }
    }

    /** One acquire on the way up, one release on the way down, in that order and no more. */
    @Test
    public void theLockIsTakenOnceAndGivenBackOnce() {
        var handle = new Recorder();
        var lock = new ScreenWakeLock(handle);
        assertFalse(lock.isHeld());
        lock.set(true);
        assertTrue(lock.isHeld());
        lock.set(false);
        assertFalse(lock.isHeld());
        assertEquals(List.of("acquire", "release"), handle.calls);
    }

    /**
     * Edge-triggered, because the caller is a refresh: the daemon recomputes the whole policy on
     * every VM state change and hands down the answer, which is the same answer most of the time.
     */
    @Test
    public void askingForWhatIsAlreadyTrueReachesNothing() {
        var handle = new Recorder();
        var lock = new ScreenWakeLock(handle);
        lock.set(false);
        assertEquals(List.of(), handle.calls);
        lock.set(true);
        lock.set(true);
        lock.set(true);
        assertEquals(List.of("acquire"), handle.calls);
        lock.set(false);
        lock.set(false);
        assertEquals(List.of("acquire", "release"), handle.calls);
        // And it can be taken again afterwards: a second camera VM is a new hold, not a no-op.
        lock.set(true);
        assertEquals(List.of("acquire", "release", "acquire"), handle.calls);
        assertTrue(lock.isHeld());
    }

    /**
     * A refused acquire is not remembered as held, so the next refresh tries again -- the same
     * rule the foreground service's mask follows, for the same reason: the alternative is a VM
     * that runs its whole life without the thing one transient failure denied it.
     */
    @Test
    public void aFailedAcquireIsNotHeldAndIsRetried() {
        var handle = new Recorder();
        handle.failWith = new RuntimeException("no");
        var lock = new ScreenWakeLock(handle);
        lock.set(true);
        assertFalse(lock.isHeld());
        assertEquals(List.of("acquire"), handle.calls);
        handle.failWith = null;
        lock.set(true);
        assertTrue(lock.isHeld());
        assertEquals(List.of("acquire", "acquire"), handle.calls);
    }

    /**
     * A failed release is recorded as released all the same. There is nothing left to try, and
     * believing it is still held would block every later acquire -- which is the worse failure of
     * the two, because it is silent.
     */
    @Test
    public void aFailedReleaseStillLetsGo() {
        var handle = new Recorder();
        var lock = new ScreenWakeLock(handle);
        lock.set(true);
        handle.failWith = new RuntimeException("no");
        lock.set(false);
        assertFalse(lock.isHeld());
        handle.failWith = null;
        lock.set(true);
        assertTrue(lock.isHeld());
        assertEquals(List.of("acquire", "release", "acquire"), handle.calls);
    }

    /**
     * With no platform behind it the lock is honest about it rather than pretending: nothing is
     * held, nothing throws, and the VM still runs -- it just gets the pre-D76 behaviour, which is
     * exactly what the switch turns off deliberately.
     */
    @Test
    public void noPlatformMeansNotHeldAndNoCrash() {
        var lock = new ScreenWakeLock(null);
        lock.set(true);
        assertFalse(lock.isHeld());
        lock.set(false);
        assertFalse(lock.isHeld());
        // The same call the service makes, with the Context a unit test does not have.
        assertFalse(ScreenWakeLock.of(null).isHeld());
    }

    /**
     * The tag is what an acceptance run greps {@code dumpsys power} for, so it is pinned here:
     * {@code package:reason} is the platform's convention and B16 looks for this exact string.
     */
    @Test
    public void theTagIsTheOneTheReportsLookFor() {
        assertEquals("droidvm:camera", ScreenWakeLock.LOCK_TAG);
    }
}
