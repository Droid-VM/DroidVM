// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.widgets.row;

import android.view.View;

import androidx.annotation.NonNull;

/**
 * Ripple for rows that actually do something when tapped.
 *
 * <p>The row widgets made themselves clickable but drew nothing on touch, so a
 * settings list looked identical whether a row navigated somewhere or was a
 * read-only readout - the affordance existed only in the developer's head.
 *
 * <p>It lands on the <b>foreground</b>, not the background: a caller may have
 * given the row a background in XML, and the ripple has to draw over the row's
 * content rather than replace what is behind it. Applied from the row's own
 * {@code setOnClickListener}, so the ripple appears and disappears with the
 * listener and can never outlive it.
 */
final class RowTouchFeedback {
    private RowTouchFeedback() {
    }

    static void apply(@NonNull View row, boolean clickable) {
        if (!clickable) {
            row.setForeground(null);
            return;
        }
        try (var a = row.getContext().obtainStyledAttributes(
            new int[]{android.R.attr.selectableItemBackground})) {
            row.setForeground(a.getDrawable(0));
        }
    }
}
