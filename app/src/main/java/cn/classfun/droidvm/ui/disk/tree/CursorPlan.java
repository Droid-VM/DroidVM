// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Where every attachment cursor ends up when the tree goes from one {@link TreeShape} to
 * another. Pure: the same function predicts an operation (before it runs, for the confirmation
 * and the persisted rewrite) and reconciles the editor's in-memory cursors after any refresh.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>a cursor whose node survives stays - unless the node was a leaf that just grew its first
 *       overlay, in which case a writable cursor follows that overlay down (that is how "take a
 *       snapshot, keep going" feels), while a read-only one stays on the now-locked base;</li>
 *   <li>a cursor whose node is gone climbs the OLD ancestor chain to the nearest survivor
 *       (subtree delete: the parent; merge: the base), or is cleared when there is none;</li>
 *   <li>a cursor landing on a node with children, or on a node that another cursor also holds,
 *       is forced read-only - a base under overlays must not be written, and neither may a
 *       disk two writers share. Forcing only ever adds read-only; it never removes it.</li>
 * </ol>
 * A pinned cursor (its VM is not stopped) must not change at all; one that would is reported in
 * {@link #refused} and left untouched, and callers refuse the whole operation.
 */
public final class CursorPlan {
    public static final class Change {
        @NonNull
        public final AttachmentCursor from;
        @NonNull
        public final AttachmentCursor to;

        public Change(@NonNull AttachmentCursor from, @NonNull AttachmentCursor to) {
            this.from = from;
            this.to = to;
        }

        public boolean moved() {
            return !Objects.equals(from.nodeId, to.nodeId);
        }

        public boolean cleared() {
            return to.nodeId == null;
        }

        public boolean readonlyForced() {
            return to.readonly && !from.readonly;
        }
    }

    /** Every input cursor at its new position (refused ones unchanged), input order kept. */
    @NonNull
    public final List<AttachmentCursor> cursors = new ArrayList<>();
    /** The cursors that changed position or read-only state. */
    @NonNull
    public final List<Change> changes = new ArrayList<>();
    /** Pinned cursors the operation would have changed; non-empty means "refuse". */
    @NonNull
    public final List<AttachmentCursor> refused = new ArrayList<>();

    private CursorPlan() {
    }

    public boolean isRefused() {
        return !refused.isEmpty();
    }

    /** Changes the operation writes to the VM store itself (shadow and other-VM slots). */
    @NonNull
    public List<Change> persistedChanges() {
        var out = new ArrayList<Change>();
        for (var c : changes) if (c.from.isPersisted()) out.add(c);
        return out;
    }

    /** Changes that must be stated in the confirmation (other VMs' slots). */
    @NonNull
    public List<Change> announcedChanges() {
        var out = new ArrayList<Change>();
        for (var c : changes) if (c.from.isAnnounced()) out.add(c);
        return out;
    }

    /** Changes the editor applies to its own rows when the panel closes. */
    @NonNull
    public List<Change> liveChanges() {
        var out = new ArrayList<Change>();
        for (var c : changes) if (c.from.isLive()) out.add(c);
        return out;
    }

    /**
     * @param moving cursors to reposition
     * @param fixed  cursors already known to be correct for {@code after} (e.g. persisted slots
     *               re-read from the store after an operation); they only count towards sharing
     * @param before the shape the moving cursors refer to
     * @param after  the shape they must refer to afterwards
     */
    @NonNull
    public static CursorPlan reconcile(
        @NonNull List<AttachmentCursor> moving,
        @NonNull List<AttachmentCursor> fixed,
        @NonNull TreeShape before,
        @NonNull TreeShape after
    ) {
        var plan = new CursorPlan();
        // A node that appeared under a surviving one is a freshly created overlay; the first
        // one per parent is where that parent's writable cursors go (rule 1).
        var createdUnder = new HashMap<UUID, UUID>();
        for (var id : after.nodes()) {
            if (before.contains(id)) continue;
            var parent = after.parentOf(id);
            if (parent != null && before.contains(parent)) createdUnder.putIfAbsent(parent, id);
        }

        var targets = new ArrayList<UUID>(moving.size());
        for (var c : moving) targets.add(targetOf(c, before, after, createdUnder));

        var holders = new HashMap<UUID, Integer>();
        for (int i = 0; i < moving.size(); i++)
            if (targets.get(i) != null && moving.get(i).countsForSharing())
                holders.merge(targets.get(i), 1, Integer::sum);
        for (var f : fixed)
            if (f.nodeId != null && f.countsForSharing())
                holders.merge(f.nodeId, 1, Integer::sum);

        for (int i = 0; i < moving.size(); i++) {
            var from = moving.get(i);
            var target = targets.get(i);
            AttachmentCursor to;
            if (target == null) {
                to = from.at(null, null, from.readonly);
            } else {
                boolean forced = after.hasChildren(target)
                    || holders.getOrDefault(target, 0) >= 2;
                to = from.at(target, after.pathOf(target), from.readonly || forced);
            }
            boolean changed = !Objects.equals(from.nodeId, to.nodeId)
                || from.readonly != to.readonly;
            if (changed && from.pinned) {
                plan.refused.add(from);
                plan.cursors.add(from);
                continue;
            }
            plan.cursors.add(to);
            if (changed) plan.changes.add(new Change(from, to));
        }
        return plan;
    }

    @Nullable
    private static UUID targetOf(
        @NonNull AttachmentCursor c,
        @NonNull TreeShape before,
        @NonNull TreeShape after,
        @NonNull Map<UUID, UUID> createdUnder
    ) {
        if (c.nodeId == null) return null;
        if (after.contains(c.nodeId)) {
            var child = createdUnder.get(c.nodeId);
            if (child != null && !before.hasChildren(c.nodeId) && !c.readonly) return child;
            return c.nodeId;
        }
        var seen = new HashSet<UUID>();
        var cur = before.parentOf(c.nodeId);
        while (cur != null && seen.add(cur)) {
            if (after.contains(cur)) return cur;
            cur = before.parentOf(cur);
        }
        return null;
    }
}
