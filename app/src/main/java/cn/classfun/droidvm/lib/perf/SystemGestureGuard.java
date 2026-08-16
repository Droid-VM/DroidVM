// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.perf;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.classfun.droidvm.lib.run.RunContext;
import cn.classfun.droidvm.lib.run.RunResult;
import cn.classfun.droidvm.lib.run.root.RootRunContext;

/**
 * Suppresses OEM full-screen touch gestures while a VM display is in the foreground, so
 * multi-finger input reaches the guest instead of the host system.
 *
 * <p>Why this exists: ColorOS/OxygenOS intercepts three-finger touches globally -- swipe-down
 * takes a screenshot, touch-and-hold starts a partial screenshot -- and unlike the navigation
 * back gesture there is NO public per-app opt-out ({@code setSystemGestureExclusionRects} only
 * covers screen-edge gestures, and the game-mode declaration in {@link GamePerfHint} does not
 * suppress it either). A guest desktop, however, has its own three-finger gestures (pinch zoom,
 * workspace switch), which the host eats before the guest ever sees a pointer event.
 *
 * <p>So: while (and only while) the native display is foreground, the OEM toggles below are
 * turned off through the root shell, and restored to their previous values on exit. On devices
 * without these keys (`settings get` prints "null") this is a no-op, so calling it
 * unconditionally on every device is safe.
 *
 * <p>If the app process dies while the display is up, the exit path never runs and the user's
 * gesture setting stays off until the next display session restores it on entry -- an accepted
 * trade-off for not persisting state; the keys are re-read (not assumed) on every enter.
 */
public final class SystemGestureGuard {
    private static final String TAG = "SystemGestureGuard";

    /** OEM gesture toggles (system namespace) that swallow multi-finger touches. */
    private static final String[] KEYS = {
        // ColorOS/OxygenOS "smart apperceive" screenshot: three-finger swipe & touch-and-hold.
        "oplus_customize_smart_apperceive_screen_capture",
        // Three-finger sideways swipe to switch apps.
        "oplus_customize_three_fingers_switch_app",
    };

    /** Serializes enter/exit so a fast pause/resume cannot interleave get and put. */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Keys that were "1" on enter and must go back to "1" on exit. */
    private static final List<String> suppressed = new ArrayList<>();

    private SystemGestureGuard() {
    }

    /** Turns the OEM gestures off; call when the VM display becomes foreground. */
    public static void enterDisplay() {
        executor.execute(() -> {
            RunContext shell = RootRunContext.getContext();
            synchronized (suppressed) {
                // A re-enter without exit (activity recreation) must not re-read "0" as the
                // value to restore, so the restore list only grows from a clean slate.
                if (!suppressed.isEmpty()) return;
                for (String key : KEYS) {
                    RunResult get = shell.runQuiet("settings get system " + key);
                    if (!get.isSuccess() || !"1".equals(get.getOutString())) continue;
                    if (shell.runQuiet("settings put system " + key + " 0").isSuccess()) {
                        suppressed.add(key);
                        Log.i(TAG, "suppressed host gesture: " + key);
                    }
                }
            }
        });
    }

    /** Restores whatever {@link #enterDisplay} turned off; call when the display leaves. */
    public static void exitDisplay() {
        executor.execute(() -> {
            RunContext shell = RootRunContext.getContext();
            synchronized (suppressed) {
                for (String key : suppressed) {
                    shell.runQuiet("settings put system " + key + " 1");
                    Log.i(TAG, "restored host gesture: " + key);
                }
                suppressed.clear();
            }
        });
    }
}
