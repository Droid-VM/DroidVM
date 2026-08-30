// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/** Shared confirmation and optional writable-disk cleanup for deleting a VM. */
public final class VMDeletion {
    private static final String TAG = "VMDeletion";

    private VMDeletion() {
    }

    /** Shows the same delete options from both the VM list and VM details page. */
    public static void confirm(
        @NonNull Context context,
        @NonNull VMConfig config,
        @NonNull Consumer<Boolean> onConfirmed
    ) {
        var paths = writableDiskPaths(config);
        var layout = new LinearLayout(context);
        var deleteDisks = new CheckBox(context);
        deleteDisks.setText(context.getString(
            R.string.vm_delete_writable_disks, paths.size()));
        deleteDisks.setEnabled(!paths.isEmpty());
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, 0, pad, 0);
        layout.addView(deleteDisks);

        new MaterialAlertDialogBuilder(context)
            .setTitle(config.getName())
            .setMessage(R.string.vm_delete_confirm)
            .setView(layout)
            .setPositiveButton(R.string.vm_delete,
                (dialog, which) -> onConfirmed.accept(deleteDisks.isChecked()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Releases the VM from the daemon, then optionally removes the disk registry entries and
     * files that belonged to writable attachments. The caller must remove and save the VM store
     * first so a fresh-store scan can reliably identify references from other VMs.
     *
     * <p>{@code vm_delete} is idempotent on the daemon side: a VM it never managed (created but
     * never started) is a successful no-op, so the only failure that keeps the files is a VM it
     * could not stop.
     */
    public static void releaseDaemonAndMaybeDeleteDisks(
        @NonNull Context context,
        @NonNull VMConfig config,
        boolean deleteDisks,
        boolean vmStoreSaved
    ) {
        var appContext = context.getApplicationContext();
        var paths = writableDiskPaths(config);
        Runnable cleanup = () -> runOnPool(() -> cleanupDisks(appContext, paths));
        Runnable skipped = () -> showResult(appContext, new Outcome(0, 0, 0, paths.size()));

        var request = DaemonConnection.getInstance().buildRequest("vm_delete")
            .put("vm_id", config.getId().toString());
        if (deleteDisks && !paths.isEmpty()) {
            if (!vmStoreSaved) {
                skipped.run();
            } else {
                // A successful response means DeleteHandler has stopped and removed the VM (or
                // never had it). If the daemon cannot be reached, its owned VM processes cannot
                // still be running either, matching the disk-operation run-state guard's
                // semantics.
                request
                    .onResponse(response -> cleanup.run())
                    .onUnsuccessful(response -> {
                        Log.w(TAG, fmt("daemon refused vm_delete; keeping disk files: %s",
                            response.optString("message", "")));
                        skipped.run();
                    })
                    .onError(error -> cleanup.run());
            }
        }
        request.invoke();
    }

    @NonNull
    private static LinkedHashSet<String> writableDiskPaths(@NonNull VMConfig config) {
        var paths = new LinkedHashSet<String>();
        var disks = config.item.opt("disks", null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY)) return paths;
        for (var disk : disks.asArray()) {
            var path = disk.optString("path", "");
            if (!path.isEmpty() && !disk.optBoolean("readonly", false)) paths.add(path);
        }
        return paths;
    }

    /** What happened to the requested files, by reason, for the one toast at the end. */
    private static final class Outcome {
        final int deleted;
        final int attachedByOthers;
        final int basesOfOverlays;
        final int failed;

        Outcome(int deleted, int attachedByOthers, int basesOfOverlays, int failed) {
            this.deleted = deleted;
            this.attachedByOthers = attachedByOthers;
            this.basesOfOverlays = basesOfOverlays;
            this.failed = failed;
        }
    }

    private static void cleanupDisks(
        @NonNull Context context,
        @NonNull LinkedHashSet<String> requestedPaths
    ) {
        if (requestedPaths.isEmpty()) return;
        try {
            var vmStore = new VMStore();
            if (!vmStore.load(context)) {
                showResult(context, new Outcome(0, 0, 0, requestedPaths.size()));
                return;
            }

            // A writable disk must still be retained when any remaining VM references it,
            // including through a read-only attachment.
            var referencedPaths = new HashSet<String>();
            vmStore.forEach((id, vm) -> collectAllDiskPaths(vm, referencedPaths));
            var candidates = new LinkedHashSet<String>();
            int attachedByOthers = 0;
            for (var path : requestedPaths) {
                if (referencedPaths.contains(path)) attachedByOthers++;
                else candidates.add(path);
            }

            var diskStore = new DiskStore();
            if (!diskStore.load(context)) {
                showResult(context, new Outcome(0, attachedByOthers, 0, candidates.size()));
                return;
            }

            var safePaths = new ArrayList<String>();
            int bases = 0;
            int failed = 0;
            boolean registryChanged = false;
            boolean madeProgress;
            do {
                madeProgress = false;
                for (var path : new ArrayList<>(candidates)) {
                    var registrations = registrationsForPath(diskStore, path);
                    if (registrations.size() > 1) {
                        // Duplicate registry entries are ambiguous; leave both the registry and
                        // file untouched instead of choosing one arbitrarily.
                        candidates.remove(path);
                        failed++;
                        continue;
                    }
                    if (registrations.isEmpty()) {
                        safePaths.add(path);
                        candidates.remove(path);
                        madeProgress = true;
                        continue;
                    }
                    var disk = registrations.get(0);
                    if (diskStore.hasChildren(disk.getId())) continue;
                    diskStore.removeById(disk.getId());
                    safePaths.add(path);
                    candidates.remove(path);
                    registryChanged = true;
                    madeProgress = true;
                }
            } while (madeProgress);
            // Whatever is left is a base of overlays this VM did not own (or a base whose
            // overlays are themselves bases): those stay, with their registry entries.
            bases = candidates.size();

            // Persist the registry first. A save failure must never leave a registry entry that
            // points at a file already removed from storage.
            if (registryChanged && !diskStore.save(context)) {
                showResult(context, new Outcome(
                    0, attachedByOthers, bases, failed + safePaths.size()));
                return;
            }

            int deleted = 0;
            for (var path : safePaths) {
                var file = new File(path);
                if (!file.exists() || file.isFile() && file.delete()) deleted++;
                else failed++;
            }
            showResult(context, new Outcome(deleted, attachedByOthers, bases, failed));
        } catch (Exception e) {
            Log.w(TAG, "Failed to clean up disks after VM deletion", e);
            showResult(context, new Outcome(0, 0, 0, requestedPaths.size()));
        }
    }

    private static void collectAllDiskPaths(
        @NonNull VMConfig config,
        @NonNull Set<String> paths
    ) {
        var disks = config.item.opt("disks", null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY)) return;
        for (var disk : disks.asArray()) {
            var path = disk.optString("path", "");
            if (!path.isEmpty()) paths.add(path);
        }
    }

    @NonNull
    private static List<DiskConfig> registrationsForPath(
        @NonNull DiskStore store,
        @NonNull String path
    ) {
        var registrations = new ArrayList<DiskConfig>();
        for (int i = 0; i < store.size(); i++) {
            var disk = store.get(i);
            if (path.equals(disk.getFullPath())) registrations.add(disk);
        }
        return registrations;
    }

    private static void showResult(@NonNull Context context, @NonNull Outcome outcome) {
        var message = context.getString(R.string.vm_delete_disks_result,
            outcome.deleted, outcome.attachedByOthers, outcome.basesOfOverlays, outcome.failed);
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
