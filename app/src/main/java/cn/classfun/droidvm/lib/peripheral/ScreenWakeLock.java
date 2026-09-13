// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.peripheral;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Keeps the phone's display from turning itself off, for exactly as long as somebody wants it.
 *
 * <p>Why an app that runs virtual machines needs one at all is defect <b>D76</b>: the app's
 * {@code CAMERA} AppOps mode is {@code foreground}, so cameraserver revokes a streaming session
 * the moment the uid stops counting as foreground -- and a sleeping screen is enough to do that,
 * because it drops even a resumed activity to {@code TOP_SLEEPING}. The guest sees the revocation
 * as {@code ENODEV} on its next {@code VIDIOC_DQBUF} and the session is dead
 * ({@code logs/vpu_wp/B15-build.md} section 5.2, {@code plans/VPU_DESIGN.md} section 7.1). The app
 * cannot change the AppOps mode -- that needs {@code MANAGE_APP_OPS_MODES}, and the mode is the
 * phone owner's decision -- so the thing it can do is not let the screen go to sleep.</p>
 *
 * <p><b>Why a wake lock and not a window.</b> The alternative is a transparent activity carrying
 * {@code FLAG_KEEP_SCREEN_ON}, which is what the deprecation note on
 * {@link PowerManager#SCREEN_DIM_WAKE_LOCK} points every app at. It is the wrong shape here: the
 * thing that has to stay awake is a VM owned by a root daemon with no UI of its own, and an
 * activity is a visible window that the user can dismiss, that the recents list shows, that
 * covers whatever they were doing, and that has to be started from a service anyway. A wake lock
 * is owned by the process that holds it, has no presence on screen, and is released by the
 * platform if that process dies. The deprecation is real but it is not a removal: this tree's
 * framework still answers {@code true} for the level in
 * {@code PowerManagerService.isWakeLockLevelSupportedInternal}
 * ({@code services/core/java/com/android/server/power/PowerManagerService.java:3051}) and still
 * maps it to {@code WAKE_LOCK_SCREEN_DIM} in {@code getWakeLockSummaryFlags} ({@code :3053}),
 * which is the flag that holds the display on. {@code android.permission.WAKE_LOCK} is
 * {@code protectionLevel="normal"} ({@code core/res/AndroidManifest.xml:2827}), so the app just
 * has it. If a future platform does drop the level, the activity is the fallback -- and the
 * failure is visible, because {@code isHeld} stays false and the D76 signature comes straight
 * back.</p>
 *
 * <p><b>{@code ACQUIRE_CAUSES_WAKEUP} is asked for and may not be granted.</b> It is what turns
 * a screen that is <em>already</em> off back on, rather than only holding on one that is on. Since
 * Android V the platform requires {@code android.permission.TURN_SCREEN_ON} for it
 * ({@code PowerManagerService.REQUIRE_TURN_SCREEN_ON_PERMISSION}, checked in
 * {@code isAcquireCausesWakeupFlagAllowed}, {@code :1772-1801}) and that permission is
 * {@code signature|privileged|appop} ({@code core/res/AndroidManifest.xml:2856-2859}), which this
 * app is none of. A refusal is not an error -- the platform logs
 * {@code Not allowing device wake-up for ...} and drops the flag, keeping the lock -- so the flag
 * is passed as a best effort and nothing depends on it. What the app depends on is the level: a
 * screen that is on when the VM starts stays on. Waking a sleeping phone up first stays the rig's
 * job ({@code deploy/vpu/vm.sh wake}).</p>
 *
 * <p>The platform call is behind {@link Handle} for one reason: a JVM unit test has a stub
 * {@code PowerManager} that returns nothing, and the part worth asserting is the state machine
 * below -- that an acquire happens once on the way up, a release once on the way down, and that
 * asking twice for what is already true does nothing.</p>
 */
public final class ScreenWakeLock {
    private static final String TAG = "ScreenWakeLock";

    /**
     * The tag the platform prints for this lock, in {@code dumpsys power} and in battery stats.
     * {@code package:reason} is the convention; it is what an acceptance run greps for.
     */
    public static final String LOCK_TAG = "droidvm:camera";

    /** The whole of what this needs from the platform, so a test can be the platform. */
    public interface Handle {
        void acquire();

        void release();
    }

    @Nullable
    private final Handle handle;

    private boolean held;

    ScreenWakeLock(@Nullable Handle handle) {
        this.handle = handle;
    }

    /** A lock backed by the real {@link PowerManager}, or one that can only say it is not held. */
    @NonNull
    public static ScreenWakeLock of(@Nullable Context context) {
        return new ScreenWakeLock(platformHandle(context));
    }

    /**
     * Brings the lock in line with {@code want}: edge-triggered, so this is the call the caller
     * makes on every refresh and only the transitions reach the platform.
     *
     * <p>A failed acquire leaves the state false, which is both true and what makes the next
     * refresh try again -- the same rule the foreground service's mask follows. A failed release
     * is recorded as released anyway: there is nothing further to try, and pretending it is still
     * held would stop the next acquire from happening.</p>
     */
    public synchronized void set(boolean want) {
        if (want == held) return;
        if (handle == null) {
            if (want) Log.w(TAG, fmt("no power manager to hold %s with; the screen will sleep on "
                + "its own timeout and a camera session will not survive it", LOCK_TAG));
            return;
        }
        try {
            if (want) handle.acquire();
            else handle.release();
        } catch (Throwable t) {
            Log.w(TAG, fmt("could not %s %s", want ? "acquire" : "release", LOCK_TAG), t);
            if (want) return;
        }
        held = want;
        Log.i(TAG, fmt("%s %s", LOCK_TAG, want ? "held" : "released"));
    }

    /** Whether this believes the platform is holding the lock. */
    public synchronized boolean isHeld() {
        return held;
    }

    @Nullable
    @SuppressWarnings("deprecation")
    @SuppressLint("WakelockTimeout")
    private static Handle platformHandle(@Nullable Context context) {
        if (context == null) return null;
        try {
            var power = context.getSystemService(PowerManager.class);
            if (power == null) return null;
            // Dim rather than bright: what has to be true is that the display is on, because that
            // is what keeps the uid out of TOP_SLEEPING. How brightly it is on is the user's
            // business and nobody is looking at it -- the frames are going to a guest.
            var lock = power.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, LOCK_TAG);
            // Not reference counted: this class already tracks the one holder it has, and a
            // counted lock that is acquired twice and released once is a phone that never sleeps.
            lock.setReferenceCounted(false);
            return new Handle() {
                @Override
                public void acquire() {
                    // No timeout on purpose. The lock is bounded by the VM, which can legitimately
                    // run for hours, and a timeout would put the D76 failure back on a clock
                    // instead of removing it. What bounds it in the bad case is the process: the
                    // platform drops a wake lock when the holder dies.
                    lock.acquire();
                }

                @Override
                public void release() {
                    if (lock.isHeld()) lock.release();
                }
            };
        } catch (Throwable t) {
            Log.w(TAG, fmt("could not create %s", LOCK_TAG), t);
            return null;
        }
    }
}
