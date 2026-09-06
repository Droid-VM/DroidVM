// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.disk.DiskStore;

/**
 * The registry's parent links at one instant, plus each node's path and display name. Pure and
 * immutable; the {@code with*} builders return the shape an operation WOULD leave behind, so a
 * {@link CursorPlan} can be computed (and shown to the user) before anything runs, with the very
 * same code that reconciles attachments after it ran.
 */
public final class TreeShape {
    private final Set<UUID> nodes = new LinkedHashSet<>();
    private final Map<UUID, UUID> parentOf = new HashMap<>();
    private final Map<UUID, String> pathOf = new HashMap<>();
    private final Map<UUID, String> nameOf = new HashMap<>();

    private TreeShape() {
    }

    /** Snapshot of a whole registry; a parent id that isn't registered reads as "root". */
    @NonNull
    public static TreeShape of(@NonNull DiskStore store) {
        var shape = new TreeShape();
        for (int i = 0; i < store.size(); i++) {
            var cfg = store.get(i);
            shape.put(cfg.getId(), cfg.getFullPath(), cfg.getName(), null);
        }
        for (int i = 0; i < store.size(); i++) {
            var cfg = store.get(i);
            var parent = cfg.getParentId();
            if (parent != null && shape.nodes.contains(parent))
                shape.parentOf.put(cfg.getId(), parent);
        }
        return shape;
    }

    /** Empty shape for building by hand (tests, predictions). */
    @NonNull
    public static TreeShape empty() {
        return new TreeShape();
    }

    /** Add or replace a node; a null parent makes it a root. Returns this for chaining. */
    @NonNull
    public TreeShape put(
        @NonNull UUID id, @NonNull String path, @NonNull String name, @Nullable UUID parent) {
        nodes.add(id);
        pathOf.put(id, path);
        nameOf.put(id, name);
        if (parent == null) parentOf.remove(id);
        else parentOf.put(id, parent);
        return this;
    }

    public boolean contains(@NonNull UUID id) {
        return nodes.contains(id);
    }

    @NonNull
    public Set<UUID> nodes() {
        return new LinkedHashSet<>(nodes);
    }

    @Nullable
    public UUID parentOf(@NonNull UUID id) {
        return parentOf.get(id);
    }

    @Nullable
    public String pathOf(@NonNull UUID id) {
        return pathOf.get(id);
    }

    @Nullable
    public String nameOf(@NonNull UUID id) {
        return nameOf.get(id);
    }

    public boolean hasChildren(@NonNull UUID id) {
        return parentOf.containsValue(id);
    }

    /** {@code id} and every descendant, cycle-guarded; empty when {@code id} is unknown. */
    @NonNull
    public Set<UUID> subtreeOf(@NonNull UUID id) {
        var out = new LinkedHashSet<UUID>();
        if (!nodes.contains(id)) return out;
        var queue = new ArrayDeque<UUID>();
        queue.add(id);
        while (!queue.isEmpty()) {
            var cur = queue.poll();
            if (!out.add(cur)) continue;
            for (var e : parentOf.entrySet())
                if (cur.equals(e.getValue())) queue.add(e.getKey());
        }
        return out;
    }

    /** Root of {@code id}'s family (itself on a cycle). */
    @NonNull
    public UUID rootOf(@NonNull UUID id) {
        var seen = new HashSet<UUID>();
        var cur = id;
        while (seen.add(cur)) {
            var p = parentOf.get(cur);
            if (p == null) return cur;
            cur = p;
        }
        return id;
    }

    /** All nodes sharing a root with {@code id}. */
    @NonNull
    public Set<UUID> familyOf(@NonNull UUID id) {
        return subtreeOf(rootOf(id));
    }

    // ---- predictions -------------------------------------------------------------------------

    @NonNull
    private TreeShape copy() {
        var s = new TreeShape();
        s.nodes.addAll(nodes);
        s.parentOf.putAll(parentOf);
        s.pathOf.putAll(pathOf);
        s.nameOf.putAll(nameOf);
        return s;
    }

    /** After deleting {@code removed} (callers pass a whole subtree). */
    @NonNull
    public TreeShape without(@NonNull Set<UUID> removed) {
        var s = copy();
        for (var id : removed) {
            s.nodes.remove(id);
            s.parentOf.remove(id);
            s.pathOf.remove(id);
            s.nameOf.remove(id);
        }
        s.parentOf.values().removeIf(removed::contains);
        return s;
    }

    /** After merging {@code node} into its parent: node gone, its children re-based onto it. */
    @NonNull
    public TreeShape withMerged(@NonNull UUID node) {
        var s = copy();
        var parent = parentOf.get(node);
        var children = new ArrayList<UUID>();
        for (var e : s.parentOf.entrySet())
            if (node.equals(e.getValue())) children.add(e.getKey());
        for (var child : children) {
            if (parent == null) s.parentOf.remove(child);
            else s.parentOf.put(child, parent);
        }
        s.nodes.remove(node);
        s.parentOf.remove(node);
        s.pathOf.remove(node);
        s.nameOf.remove(node);
        return s;
    }

    /** After creating overlay {@code newId} on top of {@code under}. */
    @NonNull
    public TreeShape withChild(
        @NonNull UUID newId, @NonNull String path, @NonNull String name, @NonNull UUID under) {
        return copy().put(newId, path, name, under);
    }

    /** After flattening {@code node}: it keeps its children but becomes a root of its own. */
    @NonNull
    public TreeShape withDetached(@NonNull UUID node) {
        var s = copy();
        s.parentOf.remove(node);
        return s;
    }
}
