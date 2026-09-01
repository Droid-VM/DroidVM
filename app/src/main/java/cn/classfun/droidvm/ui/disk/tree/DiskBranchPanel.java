// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.disk.action.BackingChainLinker;
import cn.classfun.droidvm.ui.disk.action.DiskActionDialog;
import cn.classfun.droidvm.ui.disk.action.DiskOverlayCreateDialog;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursors.LiveRows;
import cn.classfun.droidvm.ui.vm.VmRunningQuery;

/**
 * The branch-management panel: the whole overlay family of a disk, every attachment on it, and
 * a per-node menu that creates/deletes/merges/flattens branches while the panel STAYS OPEN, so
 * one snapshot can be followed by the next without re-entering it.
 *
 * <p>Opened from the VM disk editor it also carries that editor's unsaved rows as in-memory
 * {@link AttachmentCursor}s. Those follow every operation by the rules in {@link CursorPlan} -
 * the row that opened the panel is the active cursor - and are handed back in the
 * {@link Result} when the panel closes: OK re-points the active row at whatever is selected,
 * Back/Close at wherever its cursor drifted to (a cursor with nothing left under it removes the
 * row). Other VMs' saved slots are rewritten by the operations themselves, after being announced
 * in their confirmations; that part happens whether or not the editor is later saved.
 *
 * <p>Merge and flatten run in another activity; the panel waits underneath and refreshes when
 * its window regains focus. After every refresh the view re-roots on the active cursor's node,
 * so a flattened branch shows as its own family from then on.
 */
public final class DiskBranchPanel {
    private static final String TAG = "DiskBranchPanel";

    public interface Listener {
        /** The registry changed under the panel (main thread); hosts refresh their own lists. */
        default void onRegistryChanged() {
        }

        /** The panel closed (main thread). */
        void onClosed(@NonNull Result result);
    }

    public static final class Result {
        /** OK pressed (editor mode only); Back/Close/outside-tap otherwise. */
        public final boolean confirmed;
        /**
         * Editor row index to its new path, only for rows that must change. A null value means
         * the row's whole tree is gone and the row is to be removed.
         */
        @NonNull
        public final Map<Integer, String> rows;
        /** The disk the panel was opened for no longer exists. */
        public final boolean subjectGone;

        Result(boolean confirmed, @NonNull Map<Integer, String> rows, boolean subjectGone) {
            this.confirmed = confirmed;
            this.rows = rows;
            this.subjectGone = subjectGone;
        }
    }

    private final Context context;
    private final UUID subjectId;
    @Nullable
    private final LiveRows live;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    // All written on the main thread; refresh() reads them from the pool only after being
    // posted from the main thread, so it always sees the latest values.
    private TreeShape shape = TreeShape.empty();
    private List<AttachmentCursor> liveCursors = new ArrayList<>();
    private boolean first = true;

    /** Where the active cursor sat at the last render; a radio left on it follows its moves. */
    @Nullable
    private UUID lastActiveNode;

    private DiskTreeView tree;
    private TextView emptyView;
    private AlertDialog dialog;
    private ViewTreeObserver.OnWindowFocusChangeListener focusListener;
    private boolean confirmed;
    private boolean refreshOnFocus;
    private boolean closed;

    private DiskBranchPanel(
        @NonNull Context context,
        @NonNull UUID subjectId,
        @Nullable LiveRows live,
        @NonNull Listener listener
    ) {
        this.context = context;
        this.subjectId = subjectId;
        this.live = live;
        this.listener = listener;
    }

    /**
     * Branch management for the disk at {@code currentPath}. Always available, even for a disk
     * with no relatives yet - creating the first overlay is one of the actions here.
     *
     * @param live the disk editor's rows when opened from one (selection enabled, OK/Back
     *             buttons); null from the disk info screen (Close button only)
     */
    public static void open(
        @NonNull Context context,
        @NonNull String currentPath,
        @Nullable LiveRows live,
        @NonNull Listener listener
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
            BackingChainLinker.repair(context, current.getId(), () ->
                new DiskBranchPanel(context, current.getId(), live, listener).refresh());
        });
    }

    /** Reload both stores, move the in-memory cursors along, re-root and repaint. */
    private void refresh() {
        runOnPool(() -> {
            try {
                var disks = new DiskStore();
                disks.load(context);
                var vms = new VMStore();
                vms.load(vms, context);
                var newShape = TreeShape.of(disks);
                var all = newShape.nodes();
                var inUse = VmRunningQuery.inUseAmong(
                    AttachmentCursors.allVmNames(vms, live == null ? null : live.vmName));
                var persisted = AttachmentCursors.collectPersisted(
                    disks, vms, all, live == null ? null : live.vmId, inUse);
                List<AttachmentCursor> liveNow;
                if (first) {
                    liveNow = live == null ? List.of()
                        : AttachmentCursors.collectLive(disks, all, live, inUse);
                } else {
                    var plan = CursorPlan.reconcile(liveCursors, persisted, shape, newShape);
                    boolean pinned = live != null && live.vmId != null
                        && inUse.contains(live.vmName);
                    liveNow = new ArrayList<>();
                    for (var c : plan.cursors) liveNow.add(c.withPinned(pinned));
                }
                UUID focus = null;
                for (var c : liveNow)
                    if (c.kind == AttachmentCursor.Kind.ACTIVE && c.nodeId != null)
                        focus = c.nodeId;
                if (focus == null && newShape.contains(subjectId)) focus = subjectId;
                var family = focus == null ? null : DiskTree.buildFamily(disks, focus);
                var familyIds = focus == null ? Set.<UUID>of() : newShape.familyOf(focus);
                var labels = labels(familyIds, liveNow, persisted);
                final var activeNode = focus == null ? null : activeNodeOf(liveNow);
                final var finalLive = liveNow;
                main.post(() -> {
                    if (closed) return;
                    shape = newShape;
                    liveCursors = finalLive;
                    boolean wasFirst = first;
                    first = false;
                    if (dialog == null) build();
                    render(family, familyIds, labels, activeNode);
                    if (!wasFirst) listener.onRegistryChanged();
                });
            } catch (Exception e) {
                Log.w(TAG, "branch panel refresh failed", e);
            }
        });
    }

    @Nullable
    private static UUID activeNodeOf(@NonNull List<AttachmentCursor> cursors) {
        for (var c : cursors)
            if (c.kind == AttachmentCursor.Kind.ACTIVE) return c.nodeId;
        return null;
    }

    /** "Attached: vm (#1), other (#2, in use)" per node; the active row is marked separately. */
    @NonNull
    private Map<UUID, String> labels(
        @NonNull Set<UUID> family,
        @NonNull List<AttachmentCursor> liveNow,
        @NonNull List<AttachmentCursor> persisted
    ) {
        var per = new HashMap<UUID, List<String>>();
        var all = new ArrayList<AttachmentCursor>(liveNow);
        all.addAll(persisted);
        for (var c : all) {
            if (c.nodeId == null || !family.contains(c.nodeId)) continue;
            if (c.kind == AttachmentCursor.Kind.ACTIVE || c.kind == AttachmentCursor.Kind.SHADOW)
                continue;
            var who = c.vmName.isEmpty() ? fmt("#%d", c.slot + 1)
                : fmt("%s (#%d)", c.vmName, c.slot + 1);
            if (c.pinned) who = context.getString(R.string.disk_tree_in_use, who);
            per.computeIfAbsent(c.nodeId, k -> new ArrayList<>()).add(who);
        }
        var out = new HashMap<UUID, String>();
        for (var e : per.entrySet())
            out.put(e.getKey(), context.getString(
                R.string.disk_tree_attached_by, String.join(", ", e.getValue())));
        return out;
    }

    private void build() {
        boolean selectable = live != null;
        tree = new DiskTreeView(context);
        tree.configure(selectable, true, null, new DiskTreeView.Listener() {
            @Override
            public void onNodeMenu(@NonNull View anchor, @NonNull DiskTree.Node node) {
                showNodeMenu(anchor, node);
            }
        });
        emptyView = new TextView(context);
        emptyView.setText(R.string.disk_tree_empty);
        emptyView.setGravity(Gravity.CENTER);
        int pad = Math.round(24 * context.getResources().getDisplayMetrics().density);
        emptyView.setPadding(pad, pad, pad, pad);
        emptyView.setVisibility(View.GONE);
        var container = new FrameLayout(context);
        container.addView(tree);
        container.addView(emptyView);
        var builder = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disk_manage_branches)
            .setView(container);
        if (selectable) {
            builder.setPositiveButton(android.R.string.ok, (d, w) -> confirmed = true)
                .setNegativeButton(R.string.disk_manage_branches_back, null);
        } else {
            builder.setPositiveButton(R.string.disk_manage_branches_close, null);
        }
        dialog = builder.create();
        dialog.setOnDismissListener(d -> onDismissed());
        dialog.show();
        var window = dialog.getWindow();
        if (window != null) {
            focusListener = hasFocus -> {
                if (hasFocus && refreshOnFocus) {
                    refreshOnFocus = false;
                    refresh();
                }
            };
            window.getDecorView().getViewTreeObserver()
                .addOnWindowFocusChangeListener(focusListener);
        }
    }

    private void render(
        @Nullable DiskTree.Node family,
        @NonNull Set<UUID> familyIds,
        @NonNull Map<UUID, String> labels,
        @Nullable UUID activeNode
    ) {
        if (family == null) {
            tree.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            tree.updateRoots(List.of());
            lastActiveNode = null;
            return;
        }
        tree.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        tree.updateRoots(List.of(family));
        tree.setCursorLabels(labels);
        tree.setCurrentId(activeNode);
        if (live != null) {
            // The radio tracks the active cursor as long as the user left it there (so OK after
            // a snapshot lands on the new overlay, same as Back would); a radio moved elsewhere
            // stays put unless that node is gone.
            var selected = tree.getSelectedId();
            if (selected == null || !familyIds.contains(selected)
                || Objects.equals(selected, lastActiveNode))
                tree.setSelectedId(activeNode != null ? activeNode : family.id());
        }
        lastActiveNode = activeNode;
    }

    /**
     * Per-node actions. None of them closes the panel: create and delete report back when the
     * registry is written, merge and flatten hand over to another activity and the panel
     * refreshes on return. Each states up front, in its own confirmation, where other VMs'
     * attachments go; the editor's rows follow silently and are applied when the panel closes.
     */
    private void showNodeMenu(@NonNull View anchor, @NonNull DiskTree.Node node) {
        var popup = new MaterialMenu(context, anchor);
        popup.inflate(R.menu.menu_disk_tree_node);
        popup.setOnMenuItemClickListener(item -> {
            var actions = new DiskActionDialog(context, null, null);
            int id = item.getItemId();
            if (id == R.id.menu_disk_create_increment) {
                new DiskOverlayCreateDialog(context, node.config, this::refresh, null)
                    .setLiveRows(live)
                    .show();
                return true;
            } else if (id == R.id.menu_disk_merge) {
                actions.tryMerge(node.config, live, () -> refreshOnFocus = true);
                return true;
            } else if (id == R.id.menu_disk_flatten) {
                actions.tryFlatten(node.config, () -> refreshOnFocus = true);
                return true;
            } else if (id == R.id.menu_disk_delete) {
                actions.confirmDelete(node.config, live, this::refresh);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void onDismissed() {
        if (closed) return;
        closed = true;
        var window = dialog.getWindow();
        if (window != null && focusListener != null) {
            var observer = window.getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(focusListener);
        }
        var rows = new LinkedHashMap<Integer, String>();
        if (live != null) {
            var selected = confirmed ? tree.getSelectedId() : null;
            for (var c : liveCursors) {
                String path;
                if (c.kind == AttachmentCursor.Kind.ACTIVE
                    && selected != null && shape.contains(selected)) {
                    path = shape.pathOf(selected);
                } else {
                    path = c.path;
                }
                var original = c.slot < live.paths.size() ? live.paths.get(c.slot) : null;
                if (path == null || !path.equals(original)) rows.put(c.slot, path);
            }
        }
        listener.onClosed(new Result(confirmed, rows, !shape.contains(subjectId)));
    }
}
