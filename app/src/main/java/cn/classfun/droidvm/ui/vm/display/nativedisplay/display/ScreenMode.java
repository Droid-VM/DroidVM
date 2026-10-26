// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import android.content.pm.ActivityInfo;

import androidx.annotation.NonNull;

/**
 * The Screen orientation a console asks Android for. Independent of the Display orientation (the
 * turn of the guest's picture in the console's window).
 */
public enum ScreenMode {
    /** Android turns the console with the device, all four ways, unless the system's rotation lock is on. */
    FOLLOW_SYSTEM(ActivityInfo.SCREEN_ORIENTATION_FULL_USER),
    /** Landscape: either way up, as the system's rotation lock allows. */
    LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE),
    /** Portrait: either way up, as the system's rotation lock allows. */
    PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);

    /** What Activity.setRequestedOrientation is given. */
    public final int request;

    ScreenMode(int request) {
        this.request = request;
    }

    /** The one the Screen button turns to: landscape, portrait, the system's, and round again. */
    @NonNull
    public ScreenMode next() {
        switch (this) {
            case LANDSCAPE:
                return PORTRAIT;
            case PORTRAIT:
                return FOLLOW_SYSTEM;
            default:
                return LANDSCAPE;
        }
    }

    @NonNull
    public static ScreenMode fromOrdinal(int ordinal) {
        var modes = values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : FOLLOW_SYSTEM;
    }

    /**
     * How the system answered a forced orientation. Large screens may not turn the display for one
     * app: Android 16 ignores the request of an app targeting API 36 or later on a display at least
     * 600 dp across, and a vendor that keeps such a display still (ignoreOrientationRequest) gives an
     * older app a window of the asked shape inside it, with bars beside it.
     */
    public enum Outcome {
        /** Nothing forced: the console follows the system. */
        FOLLOWING,
        /** The display has the asked shape: it turned to it, or was held that way already. */
        ROTATED,
        /** The display kept its shape; the console's window has the asked one, with bars beside it. */
        LETTERBOXED,
        /** Neither has the asked shape: the request was ignored. */
        IGNORED,
    }

    /**
     * @param displayWidth the display's size as it is now turned (the maximum window metrics)
     * @param windowWidth  the console's window (the current window metrics)
     */
    @NonNull
    public Outcome outcome(int displayWidth, int displayHeight, int windowWidth, int windowHeight) {
        if (this == FOLLOW_SYSTEM) return Outcome.FOLLOWING;
        boolean landscape = this == LANDSCAPE;
        if ((displayWidth > displayHeight) == landscape) return Outcome.ROTATED;
        if ((windowWidth > windowHeight) == landscape) return Outcome.LETTERBOXED;
        return Outcome.IGNORED;
    }

    /**
     * Whether Android turns the console's window when the device turns, so that the guest must not:
     * when it follows the system (and, with the system's rotation lock on, neither turns), and when a
     * forced orientation does not hold the display still but the system turns it with the device.
     *
     * @param displayKeepsRequests false once this display was seen not turning for a forced
     *                             orientation: a forced orientation that matches it is then
     *                             only the device held that way
     */
    public boolean windowFollowsDevice(@NonNull Outcome outcome, boolean displayKeepsRequests,
                                       boolean systemAutoRotate) {
        if (this == FOLLOW_SYSTEM) return true;
        boolean heldStill = outcome == Outcome.ROTATED && displayKeepsRequests;
        return !heldStill && systemAutoRotate;
    }
}
