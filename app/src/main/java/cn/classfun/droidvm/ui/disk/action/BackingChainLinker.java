// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.dirname;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.disk.tree.DiskTree;

/**
 * Post-import chain resolution for a just-registered qcow2. The whole backing chain is walked
 * FIRST (headers only, fast), then at most one dialog asks about everything found:
 * <ul>
 *   <li>relative backing paths are rewritten absolute in the header ({@code qemu-img rebase -u},
 *       instant, header-only) - qemu resolves them against the overlay's directory but crosvm
 *       does not, and the registry requires absolute paths anyway;</li>
 *   <li>parents already registered are linked ({@code parent} field);</li>
 *   <li>parents that exist on disk but aren't registered are listed in one dialog and, on
 *       confirmation, registered and linked in chain order;</li>
 *   <li>a missing parent file stops the walk with a warning - the overlay is registered but
 *       unusable until the file returns.</li>
 * </ul>
 */
public final class BackingChainLinker {
    private static final String TAG = "BackingChainLinker";

    private BackingChainLinker() {
    }

    /** One hop of the walked chain, child-first order. */
    private static final class Hop {
        final String childPath;
        final String parentPath;
        @Nullable
        final UUID registeredParent;

        Hop(String childPath, String parentPath, @Nullable UUID registeredParent) {
            this.childPath = childPath;
            this.parentPath = parentPath;
            this.registeredParent = registeredParent;
        }
    }

    /**
     * Lazily reconcile one disk's {@code parent} links with its qcow2 headers, walking upward
     * from the disk itself. Registers parents that exist but aren't in the registry and fills in
     * missing links for ones that are - silently, with no dialog, because this runs on paths
     * where the answer is never in doubt: an image whose backing files are all present is
     * exactly the case where linking is correct, and one with a missing backing file cannot be
     * used at all (the VM start guard reports that separately).
     *
     * <p>Registries predating the overlay tree, and images rebased outside the app, converge the
     * first time they're started or opened in branch management. {@code onDone} always runs on
     * the main thread.
     */
    public static void repair(
        @NonNull Context context, @NonNull UUID diskId, @Nullable Runnable onDone) {
        var main = new Handler(Looper.getMainLooper());
        runOnPool(() -> {
            try {
                var store = new DiskStore();
                store.load(context);
                var config = store.findById(diskId);
                if (config != null) {
                    var hops = new ArrayList<Hop>();
                    walkChain(store, config.getFullPath(), hops);
                    if (!hops.isEmpty()) {
                        var pending = new ArrayList<Hop>();
                        for (var hop : hops)
                            if (hop.registeredParent == null) pending.add(hop);
                        if (!pending.isEmpty())
                            registerParents(context, pending, null);
                        applyKnownLinks(context, hops);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "chain repair failed", e);
            }
            if (onDone != null) main.post(onDone);
        });
    }

    /** Write the {@code parent} links for hops whose parent is (now) registered. */
    private static void applyKnownLinks(@NonNull Context context, @NonNull List<Hop> hops) {
        try {
            var store = new DiskStore();
            store.load(context);
            boolean changed = false;
            for (var hop : hops) {
                var child = store.findByPath(hop.childPath);
                var parent = store.findByPath(hop.parentPath);
                if (child == null || parent == null) continue;
                if (!parent.getId().equals(child.getParentId())) {
                    child.setParentId(parent.getId());
                    changed = true;
                }
            }
            if (changed) store.save(context);
        } catch (Exception e) {
            Log.w(TAG, "link write-back failed", e);
        }
    }

    /** {@code onUpdate} runs on the main thread on every outcome, so callers can chain on it. */
    public static void link(
        @NonNull Context context, @NonNull UUID diskId, @Nullable Runnable onUpdate) {
        Runnable done = () -> {
            if (onUpdate != null)
                new Handler(Looper.getMainLooper()).post(onUpdate);
        };
        runOnPool(() -> {
            try {
                var store = new DiskStore();
                store.load(context);
                var config = store.findById(diskId);
                if (config == null) {
                    done.run();
                    return;
                }
                var hops = new ArrayList<Hop>();
                String missingParent = walkChain(store, config.getFullPath(), hops);
                if (hops.isEmpty() && missingParent == null) {
                    done.run();
                    return;
                }

                // Apply links that need no confirmation (parent already registered).
                boolean changed = false;
                for (var hop : hops) {
                    if (hop.registeredParent == null) continue;
                    var child = store.findByPath(hop.childPath);
                    if (child != null
                        && !hop.registeredParent.equals(child.getParentId())) {
                        child.setParentId(hop.registeredParent);
                        changed = true;
                    }
                }
                if (changed) store.save(context);

                var toImport = new ArrayList<Hop>();
                for (var hop : hops)
                    if (hop.registeredParent == null) toImport.add(hop);

                final var missing = missingParent;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (missing != null) {
                        new MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.disk_tree_broken_parent)
                            .setMessage(context.getString(
                                R.string.disk_chain_missing_parent, missing))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    }
                    if (toImport.isEmpty()) {
                        if (onUpdate != null) onUpdate.run();
                        return;
                    }
                    var names = new StringBuilder();
                    for (var hop : toImport)
                        names.append("\n- ").append(basename(hop.parentPath));
                    new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.disk_chain_import_title)
                        .setMessage(context.getString(
                            R.string.disk_chain_import_message, names.toString()))
                        .setPositiveButton(R.string.disk_chain_import_confirm, (d, w) ->
                            runOnPool(() -> registerParents(context, toImport, onUpdate)))
                        .setNegativeButton(android.R.string.cancel, (d, w) -> {
                            if (onUpdate != null) onUpdate.run();
                        })
                        .show();
                });
            } catch (Exception e) {
                Log.w(TAG, "backing chain link failed", e);
                done.run();
            }
        });
    }

    /**
     * Walk the chain upward from {@code startPath}, absolutizing headers as it goes. Fills
     * {@code hops} child-first; returns the path of a missing parent file, or null.
     */
    @Nullable
    private static String walkChain(
        @NonNull DiskStore store, @NonNull String startPath, @NonNull List<Hop> hops) {
        var current = startPath;
        var seen = new HashSet<String>();
        for (int depth = 0; depth < DiskTree.MAX_DEPTH && seen.add(current); depth++) {
            String backing;
            String backingRaw;
            try {
                var info = ImageUtils.getImageInfo(current);
                backingRaw = info.optString("backing-filename", "");
                if (backingRaw.isEmpty()) return null;
                backing = info.optString("full-backing-filename", backingRaw);
            } catch (Exception e) {
                return null; // unreadable image - nothing to link
            }
            if (!backing.startsWith("/"))
                backing = pathJoin(dirname(current), backing);
            if (!backingRaw.equals(backing))
                rebaseAbsolute(current, backing);
            if (!new File(backing).exists())
                return backing;
            var parent = store.findByPath(backing);
            hops.add(new Hop(current, backing,
                parent == null ? null : parent.getId()));
            current = backing;
        }
        return null;
    }

    /** Header-only rewrite to an absolute backing path; content-identical, so -u is correct. */
    private static void rebaseAbsolute(@NonNull String overlay, @NonNull String absBacking) {
        try {
            var format = ImageUtils.getImageInfo(absBacking).optString("format", "qcow2");
            var result = runListQuiet(
                getPrebuiltBinaryPath("qemu-img"), "rebase",
                "-u", "-b", absBacking, "-F", format, overlay);
            if (!result.isSuccess()) result.printLog(TAG);
        } catch (Exception e) {
        }
    }

    /**
     * Repair every registered disk's chain links, for a whole-VM pre-start pass. Blocking; call
     * off the main thread.
     */
    public static void repairAllBlocking(
        @NonNull Context context, @NonNull List<String> paths) {
        try {
            var store = new DiskStore();
            store.load(context);
            var hops = new ArrayList<Hop>();
            for (var path : paths) {
                var config = store.findByPath(path);
                if (config != null) walkChain(store, config.getFullPath(), hops);
            }
            if (hops.isEmpty()) return;
            var pending = new ArrayList<Hop>();
            for (var hop : hops)
                if (hop.registeredParent == null) pending.add(hop);
            if (!pending.isEmpty()) registerParents(context, pending, null);
            applyKnownLinks(context, hops);
        } catch (Exception e) {
            Log.w(TAG, "bulk chain repair failed", e);
        }
    }

    /** Register the unregistered parents (root-first so links resolve) and connect the chain. */
    private static void registerParents(
        @NonNull Context context, @NonNull List<Hop> toImport, @Nullable Runnable onUpdate) {
        try {
            var store = new DiskStore();
            store.load(context);
            for (int i = toImport.size() - 1; i >= 0; i--) {
                var hop = toImport.get(i);
                var parent = store.findByPath(hop.parentPath);
                if (parent == null) {
                    parent = new DiskConfig();
                    parent.setName(basename(hop.parentPath));
                    parent.item.set("folder", dirname(hop.parentPath));
                    // The parent may itself be an overlay whose own parent was walked later
                    // in the chain (i.e. earlier in this loop, since we go root-first).
                    var grand = store.findByPath(grandparentOf(hop.parentPath));
                    if (grand != null) parent.setParentId(grand.getId());
                    store.add(parent);
                }
                var child = store.findByPath(hop.childPath);
                if (child != null) child.setParentId(parent.getId());
            }
            store.save(context);
        } catch (Exception e) {
            Log.w(TAG, "parent registration failed", e);
        }
        if (onUpdate != null)
            new Handler(Looper.getMainLooper()).post(onUpdate);
    }

    @NonNull
    private static String grandparentOf(@NonNull String path) {
        try {
            var info = ImageUtils.getImageInfo(path);
            var backing = info.optString("full-backing-filename",
                info.optString("backing-filename", ""));
            if (!backing.isEmpty() && !backing.startsWith("/"))
                backing = pathJoin(dirname(path), backing);
            return backing;
        } catch (Exception e) {
            return "";
        }
    }
}
