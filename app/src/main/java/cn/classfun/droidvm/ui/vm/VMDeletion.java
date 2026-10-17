// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.DiskConfigClient;
import cn.classfun.droidvm.lib.daemon.DiskOperationClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import cn.classfun.droidvm.lib.store.base.VmConfigOwnership;
import org.json.JSONObject;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/** Shared confirmation and optional writable-disk cleanup for deleting a VM. */
public final class VMDeletion {
    private static final String TAG = "VMDeletion";

    private VMDeletion() {
    }

    /** Prepare the confirmation baseline off-thread, then report success only after deletion. */
    public static void confirm(
        @NonNull Context context,
        @NonNull VMConfig config,
        @NonNull Runnable onDeleted
    ) {
        var appContext = context.getApplicationContext();
        runOnPool(() -> {
            try {
                var client = new VmConfigClient(request -> DaemonConnection.getInstance().request(request));
                var session = client.supported() ? client.edit() : null;
                var store = session == null ? new VMStore() : new VMStore(session.document());
                if (session == null && !store.load(appContext)) throw new IOException("Cannot read VM configuration");
                var current = store.findById(config.getId());
                if (current == null) throw new IOException("VM no longer exists");
                var paths = VmDiskSharing.attachedPaths(current, true);
                paths.removeAll(VmDiskSharing.pathsAttachedByOthers(store, current.getId()));
                new Handler(Looper.getMainLooper()).post(() ->
                    show(context, current, paths.size(), deleteDisks -> runOnPool(() -> {
                        try {
                            if (session != null) session.deleteVm(current.getId().toString());
                            else deleteLegacy(appContext, current);
                            if (deleteDisks) cleanupDisks(appContext, current.getId(), VmDiskSharing.attachedPaths(current, true));
                            new Handler(Looper.getMainLooper()).post(onDeleted);
                        } catch (Exception error) { showDeleteError(appContext, error); }
                    })));
            } catch (Exception error) { showDeleteError(appContext, error); }
        });
    }

    private static void deleteLegacy(Context context, VMConfig confirmed) throws Exception {
        // An acknowledgement precedes any persistent removal. Losing it retains
        // both the configuration and disks, even if the daemon actually deleted.
        var response = DaemonConnection.getInstance().request(new JSONObject()
            .put("command", "vm_delete").put("vm_id", confirmed.getId().toString()));
        if (!response.getBoolean("success")) throw new IOException(response.optString("message", "Deletion refused"));
        VmConfigOwnership.legacyWrite(context.getFilesDir(), () -> {
            var fresh = new VMStore();
            if (!fresh.load(context)) throw new IOException("Cannot read VM configuration");
            var current = fresh.findById(confirmed.getId());
            if (current == null || !current.toJson().toString().equals(confirmed.toJson().toString()))
                throw new IOException("VM changed since deletion was confirmed");
            fresh.removeById(confirmed.getId());
            if (!fresh.save(context)) throw new IOException("Cannot save VM deletion");
        });
    }

    private static void showDeleteError(Context context, Exception error) {
        Log.w(TAG, "VM deletion failed or its result is uncertain; retaining disks", error);
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, R.string.vm_delete_error, Toast.LENGTH_LONG).show());
    }

    private static void show(
        @NonNull Context context,
        @NonNull VMConfig config,
        int diskCount,
        @NonNull Consumer<Boolean> onConfirmed
    ) {
        if (context instanceof Activity) {
            var activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) return;
        }
        var layout = new LinearLayout(context);
        var deleteDisks = new CheckBox(context);
        deleteDisks.setText(context.getString(R.string.vm_delete_writable_disks, diskCount));
        deleteDisks.setEnabled(diskCount > 0);
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
        @NonNull UUID vmId,
        @NonNull LinkedHashSet<String> requestedPaths
    ) {
        if (requestedPaths.isEmpty()) return;
        try {
            var operations = new DiskOperationClient(request -> DaemonConnection.getInstance().request(request));
            if (operations.actions().contains("delete")) {
                cleanupNativeDisks(context, vmId, requestedPaths, operations);
                return;
            }
        } catch (Exception error) {
            Log.w(TAG, "Native disk cleanup unavailable", error);
            showResult(context, new Outcome(0, 0, 0, requestedPaths.size()));
            return;
        }
        try {
            // Config attachments cannot change or transition to native ownership
            // between the reference scan and file removal. Native cleanup needs
            // its own service transaction; the persistent gate refuses it here.
            VmConfigOwnership.legacyWrite(context.getFilesDir(), () -> cleanupLegacyDisks(context, vmId, requestedPaths));
        } catch (Exception error) {
            Log.w(TAG, "Disk cleanup requires confirmed configuration ownership", error);
            showResult(context, new Outcome(0, 0, 0, requestedPaths.size()));
        }
    }

    private static void cleanupLegacyDisks(Context context, UUID vmId, LinkedHashSet<String> requestedPaths) {
        try {
            var vmStore = new VMStore();
            if (!vmStore.load(context) || vmStore.findById(vmId) != null) {
                showResult(context, new Outcome(0, 0, 0, requestedPaths.size()));
                return;
            }

            // A writable disk must still be retained when any remaining VM references it,
            // including through a read-only attachment. The deleted VM is out of the saved store
            // by now; a recreated VM with the same id was rejected above.
            var referencedPaths = VmDiskSharing.pathsAttachedByOthers(vmStore, vmId);
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

    /**
     * Native owner: each registered leaf goes through one leased daemon delete (row and file
     * together), repeated so overlays go before their bases; references are re-read each round.
     */
    private static void cleanupNativeDisks(
        @NonNull Context context,
        @NonNull UUID vmId,
        @NonNull LinkedHashSet<String> requestedPaths,
        @NonNull DiskOperationClient operations
    ) {
        var rpc = (VmConfigClient.Rpc) request -> DaemonConnection.getInstance().request(request);
        int deleted = 0, attachedByOthers = 0, failed = 0;
        var candidates = new LinkedHashSet<>(requestedPaths);
        try {
            var vmStore = new VMStore(new VmConfigClient(rpc).read().document());
            if (vmStore.findById(vmId) != null) {
                showResult(context, new Outcome(0, 0, 0, requestedPaths.size()));
                return;
            }
            var referenced = VmDiskSharing.pathsAttachedByOthers(vmStore, vmId);
            for (var path : requestedPaths) {
                if (referenced.contains(path)) { attachedByOthers++; candidates.remove(path); }
            }
            boolean progress;
            do {
                progress = false;
                var diskStore = new DiskStore(new DiskConfigClient(rpc).read().document());
                for (var path : new ArrayList<>(candidates)) {
                    var registrations = registrationsForPath(diskStore, path);
                    if (registrations.size() > 1) { candidates.remove(path); failed++; continue; }
                    if (registrations.isEmpty()) {
                        // Not a registered image: outside the daemon's registry, as before.
                        var file = new File(path);
                        if (!file.exists() || file.isFile() && file.delete()) deleted++;
                        else failed++;
                        candidates.remove(path);
                        continue;
                    }
                    var disk = registrations.get(0);
                    if (diskStore.hasChildren(disk.getId())) continue;
                    var status = operations.await(operations.start(new JSONObject().put("action", "delete")
                        .put("disk_id", disk.getId().toString()).put("delete_files", true)));
                    candidates.remove(path);
                    if (status.succeeded()) deleted++;
                    else failed++;
                    progress = true;
                    break; // The registry changed: re-read before the next decision.
                }
            } while (progress && !candidates.isEmpty());
        } catch (Exception e) {
            Log.w(TAG, "Native disk cleanup after VM deletion stopped", e);
            failed += candidates.size();
            candidates.clear();
        }
        // Whatever is left is a base of overlays this VM did not own.
        showResult(context, new Outcome(deleted, attachedByOthers, candidates.size(), failed));
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
