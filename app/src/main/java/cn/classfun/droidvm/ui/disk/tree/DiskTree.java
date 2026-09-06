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
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;

/**
 * The overlay-relation forest, built fresh from a {@link DiskStore} snapshot (the registry's
 * {@code parent} links; the qcow2 headers remain the ground truth those links mirror). Pure
 * logic - the tree views and dialogs all render flattened output of this class.
 *
 * Malformed registries degrade instead of failing: a parent id that isn't registered marks the
 * node {@code brokenParent} and promotes it to a root; cycle members are promoted the same way
 * with the cycle edge cut. Depth is capped only visually by callers - {@link #MAX_DEPTH} matches
 * crosvm's nesting limit and is what creation flows should enforce.
 */
public final class DiskTree {
    /** crosvm's MAX_NESTING_DEPTH; creating an overlay deeper than this can't boot anyway. */
    public static final int MAX_DEPTH = 10;

    public static final class Node {
        @NonNull
        public final DiskConfig config;
        public final int depth;
        /** Parent link exists but the parent isn't registered, or a cycle was cut here. */
        public final boolean brokenParent;
        @NonNull
        public final List<Node> children = new ArrayList<>();

        Node(@NonNull DiskConfig config, int depth, boolean brokenParent) {
            this.config = config;
            this.depth = depth;
            this.brokenParent = brokenParent;
        }

        @NonNull
        public UUID id() {
            return config.getId();
        }

        public boolean hasChildren() {
            return !children.isEmpty();
        }

        /** Number of descendants (the "+N" a collapsed row shows). */
        public int countDescendants() {
            int n = 0;
            for (var c : children) n += 1 + c.countDescendants();
            return n;
        }
    }

    private DiskTree() {
    }

    /** Every family in the registry: roots in registry order, children nested below them. */
    @NonNull
    public static List<Node> buildForest(@NonNull DiskStore store) {
        var kids = new HashMap<UUID, List<DiskConfig>>();
        var rootCfgs = new ArrayList<DiskConfig>();
        var broken = new HashSet<UUID>();
        for (int i = 0; i < store.size(); i++) {
            var cfg = store.get(i);
            var parentId = cfg.getParentId();
            if (parentId == null) {
                rootCfgs.add(cfg);
            } else if (store.findById(parentId) != null) {
                kids.computeIfAbsent(parentId, k -> new ArrayList<>()).add(cfg);
            } else {
                broken.add(cfg.getId());
                rootCfgs.add(cfg);
            }
        }
        var visited = new HashSet<UUID>();
        var forest = new ArrayList<Node>();
        for (var cfg : rootCfgs)
            forest.add(buildNode(cfg, 0, kids, visited, broken, false));
        // Cycle members are reachable from no root; promote each still-unvisited config (in
        // registry order, so the promotion is deterministic) - the visited guard cuts the loop.
        // Only the promoted node is marked broken; its descendants' links are intact.
        for (int i = 0; i < store.size(); i++) {
            var cfg = store.get(i);
            if (!visited.contains(cfg.getId()))
                forest.add(buildNode(cfg, 0, kids, visited, broken, true));
        }
        return forest;
    }

    /** The whole family tree containing {@code id} (walks up to the root, then expands). */
    @Nullable
    public static Node buildFamily(@NonNull DiskStore store, @NonNull UUID id) {
        var rootId = rootOf(store, id);
        for (var root : buildForest(store))
            if (root.id().equals(rootId)) return root;
        return null;
    }

    /** The root of {@code id}'s family; {@code id} itself on a broken link or cycle. */
    @NonNull
    public static UUID rootOf(@NonNull DiskStore store, @NonNull UUID id) {
        var visited = new HashSet<UUID>();
        var current = id;
        while (visited.add(current)) {
            var cfg = store.findById(current);
            if (cfg == null) break;
            var parentId = cfg.getParentId();
            if (parentId == null || store.findById(parentId) == null) return current;
            current = parentId;
        }
        return id; // cycle - treat the queried node as its own root
    }

    /**
     * Depth-first flatten for list display. A node in {@code collapsedIds} is emitted but its
     * descendants are skipped.
     */
    @NonNull
    public static List<Node> flatten(
        @NonNull List<Node> roots, @NonNull Set<UUID> collapsedIds) {
        var out = new ArrayList<Node>();
        for (var root : roots) flattenInto(root, collapsedIds, out);
        return out;
    }

    private static void flattenInto(
        @NonNull Node node, @NonNull Set<UUID> collapsedIds, @NonNull List<Node> out) {
        out.add(node);
        if (collapsedIds.contains(node.id())) return;
        for (var child : node.children) flattenInto(child, collapsedIds, out);
    }

    @NonNull
    private static Node buildNode(
        @NonNull DiskConfig cfg,
        int depth,
        @NonNull Map<UUID, List<DiskConfig>> kids,
        @NonNull Set<UUID> visited,
        @NonNull Set<UUID> broken,
        boolean cycleCut
    ) {
        visited.add(cfg.getId());
        var node = new Node(cfg, depth, cycleCut || broken.contains(cfg.getId()));
        var children = kids.get(cfg.getId());
        if (children != null) {
            for (var child : children) {
                if (visited.contains(child.getId())) continue; // cycle edge - cut
                node.children.add(buildNode(child, depth + 1, kids, visited, broken, false));
            }
        }
        return node;
    }
}
