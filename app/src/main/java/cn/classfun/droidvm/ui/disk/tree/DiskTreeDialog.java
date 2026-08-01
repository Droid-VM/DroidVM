// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.disk.action.BackingChainLinker;
import cn.classfun.droidvm.ui.disk.action.DiskActionDialog;
import cn.classfun.droidvm.ui.disk.action.DiskOverlayCreateDialog;

/**
 * The switch-branch dialog: the whole overlay family of a disk, current attachment highlighted,
 * tap a node and confirm to re-point the caller (the VM disk editor) at that branch. The
 * per-node menu creates/deletes/merges/flattens branches in place; those actions close this
 * dialog first (they run async or in other activities, and a stale tree must not stay up).
 */
public final class DiskTreeDialog {
    private DiskTreeDialog() {
    }

    /**
     * Branch management for the disk at {@code currentPath}. Always available, even for a disk
     * with no relatives yet - creating the first overlay is one of the actions here.
     *
     * @param onPick invoked with the chosen disk when it differs from the current one; null for
     *               callers that only manage the tree and don't attach anything
     */
    public static void show(
        @NonNull Context context,
        @NonNull String currentPath,
        @Nullable Consumer<DiskConfig> onPick
    ) {
        show(context, currentPath, onPick, null);
    }

    /**
     * @param onAttachmentLost invoked (main thread) when an action removes the node the caller
     *                         attaches, with the path that replaces it - the caller's row can
     *                         follow along instead of pointing at a disk that no longer exists.
     *                         The replacement is known before the action runs, so it is stated
     *                         in that action's confirmation rather than asked again afterwards.
     */
    public static void show(
        @NonNull Context context,
        @NonNull String currentPath,
        @Nullable Consumer<DiskConfig> onPick,
        @Nullable Consumer<String> onAttachmentLost
    ) {
        var main = new Handler(Looper.getMainLooper());
        runOnPool(() -> {
            var store = new DiskStore();
            store.load(context);
            var current = store.findByPath(currentPath);
            if (current == null) {
                main.post(() -> Toast.makeText(
                    context, R.string.disk_tree_not_registered, LENGTH_SHORT).show());
                return;
            }
            // Reconcile links from the images' headers before drawing: a disk whose parent was
            // never linked (registry predating the tree, or an outside rebase) should show its
            // real family the first time this opens.
            BackingChainLinker.repair(context, current.getId(), () -> runOnPool(() -> {
                var fresh = new DiskStore();
                fresh.load(context);
                var config = fresh.findById(current.getId());
                if (config == null) return;
                var family = DiskTree.buildFamily(fresh, config.getId());
                if (family == null) return;
                main.post(() -> show(context, config, family, onPick, onAttachmentLost));
            }));
        });
    }

    private static void show(
        @NonNull Context context,
        @NonNull DiskConfig current,
        @NonNull DiskTree.Node family,
        @Nullable Consumer<DiskConfig> onPick,
        @Nullable Consumer<String> onAttachmentLost
    ) {
        boolean selectable = onPick != null;
        var tree = new DiskTreeView(context);
        var dialogRef = new Object() {
            androidx.appcompat.app.AlertDialog dialog;
        };
        tree.configure(selectable, true, current.getId(), new DiskTreeView.Listener() {
            @Override
            public void onNodeMenu(@NonNull View anchor, @NonNull DiskTree.Node node) {
                showNodeMenu(context, anchor, node, dialogRef.dialog,
                    current, family, onAttachmentLost);
            }
        });
        tree.setRoots(List.of(family));
        var builder = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disk_manage_branches)
            .setView(tree);
        if (selectable) {
            builder.setPositiveButton(android.R.string.ok, (d, w) -> {
                var node = tree.getSelectedNode();
                if (node != null && !node.id().equals(current.getId()))
                    onPick.accept(node.config);
            }).setNegativeButton(android.R.string.cancel, null);
        } else {
            builder.setPositiveButton(R.string.disk_manage_branches_close, null);
        }
        dialogRef.dialog = builder.show();
    }

    /**
     * Per-node actions, each stating up front what it does to the caller's attachment so there
     * is one question and nothing to ask afterwards:
     * <ul>
     *   <li>delete takes the node's whole subtree (an overlay is meaningless without its base),
     *       so an attachment anywhere inside it moves to the node's parent - or is cleared when
     *       a family root goes and there is nothing left to attach;</li>
     *   <li>merge deletes the node itself, so an attachment on it moves to the base; an
     *       attachment on that base keeps its path but has its content rewritten, which is worth
     *       saying even though nothing appears to change;</li>
     *   <li>flatten rewrites only the node's own file - paths and contents survive it, so there
     *       is nothing to report.</li>
     * </ul>
     */
    /** The node whose children contain {@code id}, or null when {@code id} is the root. */
    @Nullable
    private static DiskTree.Node findParent(@NonNull DiskTree.Node root, @NonNull UUID id) {
        for (var child : root.children) {
            if (child.id().equals(id)) return root;
            var found = findParent(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean containsNode(@NonNull DiskTree.Node node, @NonNull UUID id) {
        if (node.id().equals(id)) return true;
        for (var child : node.children)
            if (containsNode(child, id)) return true;
        return false;
    }

    private static void showNodeMenu(
        @NonNull Context context,
        @NonNull View anchor,
        @NonNull DiskTree.Node node,
        @NonNull androidx.appcompat.app.AlertDialog treeDialog,
        @NonNull DiskConfig current,
        @NonNull DiskTree.Node family,
        @Nullable Consumer<String> onAttachmentLost
    ) {
        var parent = findParent(family, node.id());
        var replacement = parent == null ? "" : parent.config.getFullPath();
        // Only a caller that actually attaches a disk has an attachment to report on. Opened
        // from the disk info screen there is no VM, and `current` is just the disk being viewed.
        boolean attaches = onAttachmentLost != null;
        boolean attachedInSubtree = attaches && containsNode(node, current.getId());
        boolean attachedIsNode = attaches && node.id().equals(current.getId());
        boolean attachedIsParent =
            attaches && parent != null && parent.id().equals(current.getId());

        var deleteNote = !attachedInSubtree ? null
            : replacement.isEmpty()
                ? context.getString(R.string.disk_tree_attachment_cleared)
                : context.getString(R.string.disk_tree_attachment_moves, basename(replacement));
        var mergeNote = attachedIsNode
            ? context.getString(R.string.disk_tree_attachment_moves, basename(replacement))
            : attachedIsParent
                ? context.getString(R.string.disk_tree_attachment_rewritten,
                    current.getName(), node.config.getName())
                : null;
        Runnable applyReplacement = () -> {
            if (onAttachmentLost != null) onAttachmentLost.accept(replacement);
        };
        var popup = new MaterialMenu(context, anchor);
        popup.inflate(R.menu.menu_disk_tree_node);
        popup.setOnMenuItemClickListener(item -> {
            // The action runs outside this dialog (async flow or another activity); close the
            // tree so a stale view never lingers - reopening shows the updated family.
            treeDialog.dismiss();
            var actions = new DiskActionDialog(context, null, null);
            int id = item.getItemId();
            if (id == R.id.menu_disk_create_increment) {
                var create = new DiskOverlayCreateDialog(context, node.config, null, null);
                // Creating an overlay under the disk this caller attaches, with "switch to
                // overlay" chosen, moves the attachment - follow it here too, or the row would
                // still show the base and saving would undo the switch.
                if (attachedIsNode && onAttachmentLost != null)
                    create.setOnSwitchedToOverlay(onAttachmentLost::accept);
                create.show();
                return true;
            } else if (id == R.id.menu_disk_merge) {
                actions.tryMerge(node.config, mergeNote,
                    attachedIsNode ? applyReplacement : null);
                return true;
            } else if (id == R.id.menu_disk_flatten) {
                actions.tryFlatten(node.config);
                return true;
            } else if (id == R.id.menu_disk_delete) {
                actions.confirmDelete(node.config, deleteNote,
                    attachedInSubtree ? applyReplacement : null);
                return true;
            }
            return false;
        });
        popup.show();
    }
}
