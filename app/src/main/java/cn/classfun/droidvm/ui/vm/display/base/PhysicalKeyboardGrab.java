// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import cn.classfun.droidvm.display.INativeDisplayRootService;

/**
 * Asks the daemon to take the host's physical keyboard for this console, and to give it back.
 *
 * <p>The daemon does the grabbing ({@code EVIOCGRAB}, which is what lets keys Android keeps for
 * itself -- Home, the task switcher, Alt+Tab, Meta shortcuts -- reach the guest at all); this
 * class is only the decision of <em>when</em>, and there are exactly three things in it:</p>
 *
 * <ul>
 *   <li>the console is in front ({@code onResume}/{@code onPause}), because a keyboard grabbed by
 *       a console the user has left types into nothing they can see;</li>
 *   <li>the typing surface wants no IME -- {@link KeyboardMode#SYSTEM} is the one that does. A
 *       grabbed keyboard is invisible to the IME, so Chinese and every other composed input would
 *       stop working; switching to the system keyboard hands the keyboard back for exactly as
 *       long as that mode is up;</li>
 *   <li>the daemon's broker binder is connected, and this screen takes input at all.</li>
 * </ul>
 *
 * <p>The token handed to the daemon is this object's own binder, so if the console's process dies
 * the daemon hears it and releases the keyboard rather than leaving the user unable to type
 * anywhere. Calls go out on one background thread: a grab opens and scans device nodes, which is
 * not work for the main thread, and one thread keeps a release from overtaking the grab it
 * undoes.</p>
 */
public final class PhysicalKeyboardGrab {
    private static final String TAG = "PhysicalKeyboardGrab";

    @NonNull
    private final String vmId;
    @NonNull
    private final Supplier<String> screenId;
    /** Dies with this process; the daemon releases the grab when it does. */
    private final IBinder token = new Binder();
    private final ExecutorService caller = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "kbd-grab-call"));

    /** False when this screen takes no input, which leaves the keyboard to Android for good. */
    private final boolean allowed;

    @Nullable
    private INativeDisplayRootService service;
    private boolean resumed;
    private boolean wantsKeys;
    /** What was last asked for, so an unchanged state is not asked for twice. */
    private boolean requested;
    private boolean closed;

    public PhysicalKeyboardGrab(@NonNull String vmId, @NonNull Supplier<String> screenId,
                                boolean allowed) {
        this.vmId = vmId;
        this.screenId = screenId;
        this.allowed = allowed;
    }

    /** The daemon's broker binder arrived, or was lost (null). */
    public void setService(@Nullable INativeDisplayRootService service) {
        this.service = service;
        apply();
    }

    /** The console came to the front, or left it. */
    public void setResumed(boolean resumed) {
        this.resumed = resumed;
        apply();
    }

    /** The typing surface changed; only {@link KeyboardMode#SYSTEM} wants the IME to have keys. */
    public void setKeyboardMode(@NonNull KeyboardMode mode) {
        this.wantsKeys = mode != KeyboardMode.SYSTEM;
        apply();
    }

    /** Releases the keyboard and stops the caller thread; the console is going. */
    public void close() {
        if (closed) return;
        closed = true;
        if (requested) {
            requested = false;
            send(false);
        }
        caller.shutdown();
    }

    private void apply() {
        if (closed) return;
        boolean want = allowed && resumed && wantsKeys && service != null;
        if (want == requested) return;
        requested = want;
        send(want);
    }

    private void send(boolean grab) {
        var target = service;
        // A release with no binder left is already done: the daemon drops the grab when the token
        // dies, and this process holding the token is what would have died.
        if (target == null) return;
        var screen = screenId.get();
        caller.execute(() -> {
            try {
                int held = target.setKeyboardGrab(vmId, screen, grab, token);
                if (grab)
                    Log.i(TAG, held > 0
                        ? fmt("physical keyboard: %d device(s) now typing into the guest", held)
                        : "physical keyboard: armed; nothing to take yet (no keyboard attached, "
                            + "or the guest is not running)");
            } catch (Exception e) {
                Log.w(TAG, fmt("setKeyboardGrab(%b) failed", grab), e);
            }
        });
    }
}
