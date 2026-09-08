// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.ui;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

/**
 * Notices that someone is actually looking at a dialog.
 *
 * <p>A countdown exists for the start nobody is watching; the moment a finger lands on the
 * dialog that assumption is wrong, and reading the message is exactly what it must not cut
 * short. So any touch stops it -- the same thing GRUB does when a key is pressed during its
 * menu timeout.</p>
 */
public final class DialogTouch {
    private DialogTouch() {
    }

    /**
     * Runs {@code action} when the dialog itself is touched anywhere but its buttons, without
     * ever consuming the touch -- everything below keeps working exactly as it did.
     *
     * <p>The listener goes on every view in the dialog rather than only the root, because a
     * touch is reported to the view that ends up handling it: a tap on the text reaches the
     * text, a drag on a long message reaches the scroller around it, and only what nothing
     * handles reaches the panel behind them. The buttons are left out on purpose -- pressing
     * one is a decision, not a pause, and it dismisses the dialog anyway.</p>
     *
     * <p>A touch <em>outside</em> the dialog is left alone as well, though it reaches the same
     * root view: it is not someone reading, it is the dismissal gesture, and what it means is
     * the dialog's own business -- cancelling it where that is allowed, nothing where it is
     * not. Both stay exactly as they were.</p>
     *
     * <p>Call it after {@link AlertDialog#show()}: there are no views before that. Views added
     * later are not covered, and {@code action} runs on every touch, so it has to be safe to
     * run again.</p>
     */
    public static void whenTouched(@NonNull AlertDialog dialog, @NonNull Runnable action) {
        var window = dialog.getWindow();
        if (window == null) return;
        attach(window.getDecorView(), (view, event) -> {
            if (inside(view, event)) action.run();
            return false;
        });
    }

    /** Is the touch on this view, rather than past its edge? */
    private static boolean inside(@NonNull View view, @NonNull MotionEvent event) {
        var x = event.getX();
        var y = event.getY();
        return x >= 0 && y >= 0 && x <= view.getWidth() && y <= view.getHeight();
    }

    @SuppressWarnings("ClickableViewAccessibility") // observes only; never consumes
    private static void attach(@NonNull View view, @NonNull View.OnTouchListener watcher) {
        // Button covers the dialog's own three, and CompoundButton (a boot menu radio) with
        // them: both already say what they mean when they are pressed.
        if (view instanceof Button) return;
        view.setOnTouchListener(watcher);
        if (!(view instanceof ViewGroup)) return;
        var group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++)
            attach(group.getChildAt(i), watcher);
    }
}
