// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.bulletList;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;

/** Renders the announced part of a {@link CursorPlan} into confirmation text. */
public final class CursorPlanText {
    private CursorPlanText() {
    }

    /** One line per change; slots are shown 1-based, as the editor numbers them. */
    @NonNull
    public static String line(@NonNull Context ctx, @NonNull CursorPlan.Change c) {
        var vm = c.from.vmName;
        int slot = c.from.slot + 1;
        var from = c.from.path == null ? "" : basename(c.from.path);
        if (c.cleared())
            return ctx.getString(R.string.disk_tree_change_clear, vm, slot, from);
        var to = c.to.path == null ? "" : basename(c.to.path);
        if (!c.moved())
            return ctx.getString(R.string.disk_tree_change_readonly, vm, slot, from);
        return ctx.getString(c.readonlyForced()
                ? R.string.disk_tree_change_move_readonly : R.string.disk_tree_change_move,
            vm, slot, from, to);
    }

    /** A cursor whose disk keeps its path but has its content replaced (merge into base). */
    @NonNull
    public static String rewrittenLine(
        @NonNull Context ctx, @NonNull AttachmentCursor c, @NonNull String byName) {
        return ctx.getString(R.string.disk_tree_change_rewritten,
            c.vmName, c.slot + 1, c.path == null ? "" : basename(c.path), byName);
    }

    /**
     * The "other VMs' attachments change with it" paragraph, or an empty string when nothing
     * needs announcing. {@code extraLines} are appended to the same list.
     */
    @NonNull
    public static String describe(
        @NonNull Context ctx,
        @NonNull List<CursorPlan.Change> announced,
        @NonNull List<String> extraLines
    ) {
        var lines = new ArrayList<String>();
        for (var c : announced) lines.add(line(ctx, c));
        lines.addAll(extraLines);
        if (lines.isEmpty()) return "";
        return ctx.getString(R.string.disk_tree_changes_header, bulletList(lines));
    }

    @NonNull
    public static String describe(
        @NonNull Context ctx, @NonNull List<CursorPlan.Change> announced) {
        return describe(ctx, announced, List.of());
    }

    /** Refusal text naming the VMs whose pinned cursors the operation would have changed. */
    @NonNull
    public static String pinnedMessage(
        @NonNull Context ctx, @NonNull List<AttachmentCursor> refused) {
        return ctx.getString(R.string.disk_tree_pinned,
            bulletList(AttachmentCursors.vmNames(refused)));
    }
}
