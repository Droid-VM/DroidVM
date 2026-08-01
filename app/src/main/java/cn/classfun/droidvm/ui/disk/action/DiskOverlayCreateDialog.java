// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.disk.tree.DiskTree;
import cn.classfun.droidvm.ui.vm.VmRunningQuery;

/**
 * Snapshot-feel overlay creation: one name field, instant {@code qemu-img create} (an overlay is
 * just a header), the base becomes a locked parent. Every decision is collected BEFORE anything
 * executes: VMs holding the base writable are found first and the user picks - per the whole
 * batch - whether they switch to the new overlay (the "took a snapshot, keep going" default) or
 * flip to read-only; a running affected VM blocks creation outright, since its writes would
 * shift the base underneath the overlay. After confirmation the create, registry link and VM
 * updates run unattended.
 */
public final class DiskOverlayCreateDialog {
    private static final String TAG = "DiskOverlayCreate";
    /** A name this dialog generated before: {@code -ov-yyyyMMdd-HHmm} at the very end. */
    private static final Pattern OVERLAY_SUFFIX =
        Pattern.compile("-ov-\\d{8}-\\d{4}$");
    private final Handler mainLooper = new Handler(Looper.getMainLooper());
    private final Context context;
    private final DiskConfig parent;
    @Nullable
    private final Runnable onUpdate;
    @Nullable
    private final Runnable onAdvanced;
    @Nullable
    private Consumer<String> onSwitchedToOverlay;

    public DiskOverlayCreateDialog(
        @NonNull Context context,
        @NonNull DiskConfig parent,
        @Nullable Runnable onUpdate,
        @Nullable Runnable onAdvanced
    ) {
        this.context = context;
        this.parent = parent;
        this.onUpdate = onUpdate;
        this.onAdvanced = onAdvanced;
    }

    /**
     * Notified (main thread, with the new overlay's path) when the user chose to move the VMs
     * holding the base onto the overlay. A caller editing one of those VMs has its own in-memory
     * copy of the attachment - without this it would keep showing the base, and saving would
     * write that stale path back over the switch this flow just made.
     */
    public void setOnSwitchedToOverlay(@Nullable Consumer<String> listener) {
        this.onSwitchedToOverlay = listener;
    }

    public void show() {
        var view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_overlay_create, null);
        TextInputLayout layout = view.findViewById(R.id.til_overlay_name);
        TextInputEditText etName = view.findViewById(R.id.et_overlay_name);
        etName.setText(defaultName());
        var builder = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disk_create_increment)
            .setMessage(context.getString(
                R.string.disk_overlay_create_message, parent.getName()))
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null);
        if (onAdvanced != null)
            builder.setNeutralButton(R.string.disk_overlay_advanced,
                (d, w) -> onAdvanced.run());
        var dialog = builder.create();
        dialog.show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                var text = etName.getText();
                var name = text != null ? text.toString().trim() : "";
                if (name.isEmpty()) {
                    layout.setError(context.getString(R.string.disk_create_error_name_invalid));
                    return;
                }
                if (!name.toLowerCase(Locale.ROOT).endsWith(".qcow2")) name += ".qcow2";
                layout.setError(null);
                dialog.dismiss();
                gather(name);
            });
    }

    /**
     * {@code <base>-ov-<stamp>}, except that stacking overlays replaces a trailing stamp instead
     * of growing another one - a chain would otherwise read
     * {@code disk-ov-20260101-0000-ov-20260102-0000-...}.
     */
    @NonNull
    private String defaultName() {
        var base = parent.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = OVERLAY_SUFFIX.matcher(base).replaceFirst("");
        var stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
        return fmt("%s-ov-%s", base, stamp);
    }

    // Phase 1, off the main thread: validate, find affected VMs and their run state.
    private void gather(@NonNull String name) {
        var folder = parent.item.optString("folder", "");
        var overlayPath = pathJoin(folder, name);
        runOnPool(() -> {
            try {
                var store = new DiskStore();
                store.load(context);
                if (store.findByName(name) != null || new File(overlayPath).exists()) {
                    fail(context.getString(R.string.disk_create_error_exists));
                    return;
                }
                if (chainDepth(store, parent) + 1 >= DiskTree.MAX_DEPTH) {
                    fail(context.getString(
                        R.string.disk_overlay_depth_error, DiskTree.MAX_DEPTH));
                    return;
                }
                var vmStore = new VMStore();
                vmStore.load(vmStore, context);
                var parentPath = parent.getFullPath();
                var affected = new ArrayList<VMConfig>();
                for (int i = 0; i < vmStore.size(); i++) {
                    var vm = vmStore.get(i);
                    if (attachesWritable(vm, parentPath)) affected.add(vm);
                }
                if (affected.isEmpty()) {
                    execute(name, overlayPath, List.of(), false);
                    return;
                }
                var running = runningNames(affected);
                if (!running.isEmpty()) {
                    fail(context.getString(
                        R.string.disk_overlay_vm_running, "\n- " + String.join("\n- ", running)));
                    return;
                }
                var names = new ArrayList<String>();
                for (var vm : affected) names.add(vm.getName());
                mainLooper.post(() -> askVmChoice(name, overlayPath, names));
            } catch (Exception e) {
                Log.w(TAG, "overlay pre-checks failed", e);
                fail(String.valueOf(e.getMessage()));
            }
        });
    }

    // Phase 2, main thread: the one decision point - switch the affected VMs to the overlay
    // (default) or flip them read-only. Everything after runs unattended.
    private void askVmChoice(
        @NonNull String name, @NonNull String overlayPath, @NonNull List<String> vmNames) {
        var list = "\n- " + String.join("\n- ", vmNames);
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disk_overlay_vm_choice_title)
            .setMessage(context.getString(
                R.string.disk_overlay_vm_choice_message, parent.getName(), list))
            .setPositiveButton(R.string.disk_overlay_vm_switch, (d, w) ->
                runOnPool(() -> execute(name, overlayPath, vmNames, true)))
            .setNeutralButton(R.string.disk_overlay_vm_readonly, (d, w) ->
                runOnPool(() -> execute(name, overlayPath, vmNames, false)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // Phase 3, off the main thread, no further interaction: create, register, update VMs.
    private void execute(
        @NonNull String name,
        @NonNull String overlayPath,
        @NonNull List<String> vmNames,
        boolean switchVmsToOverlay
    ) {
        try {
            var parentPath = parent.getFullPath();
            var backingFormat = detectFormat(parentPath);
            var result = runList(
                getPrebuiltBinaryPath("qemu-img"), "create",
                "--format", "qcow2",
                "--backing", parentPath,
                "--backing-format", backingFormat,
                overlayPath
            );
            if (!result.isSuccess()) {
                result.printLog(TAG);
                fail(result.getErrString());
                return;
            }
            var store = new DiskStore();
            store.load(context);
            var overlay = new DiskConfig();
            overlay.setName(name);
            overlay.item.set("folder", parent.item.optString("folder", ""));
            overlay.setParentId(parent.getId());
            store.add(overlay);
            store.save(context);
            if (!vmNames.isEmpty())
                updateVms(vmNames, parentPath, overlayPath, switchVmsToOverlay);
            mainLooper.post(() -> {
                Toast.makeText(context,
                    context.getString(R.string.disk_overlay_created, name),
                    LENGTH_SHORT).show();
                if (switchVmsToOverlay && !vmNames.isEmpty() && onSwitchedToOverlay != null)
                    onSwitchedToOverlay.accept(overlayPath);
                if (onUpdate != null) onUpdate.run();
            });
        } catch (Exception e) {
            Log.e(TAG, "overlay creation failed", e);
            fail(String.valueOf(e.getMessage()));
        }
    }

    private void updateVms(
        @NonNull List<String> vmNames,
        @NonNull String parentPath,
        @NonNull String overlayPath,
        boolean switchToOverlay
    ) {
        var vmStore = new VMStore();
        if (!vmStore.load(vmStore, context)) return;
        for (int i = 0; i < vmStore.size(); i++) {
            var vm = vmStore.get(i);
            if (!vmNames.contains(vm.getName())) continue;
            var disks = vm.item.opt("disks", null);
            if (disks == null || !disks.is(DataItem.Type.ARRAY)) continue;
            for (var disk : disks.asArray()) {
                if (!parentPath.equals(disk.optString("path", ""))
                    || disk.optBoolean("readonly", false)) continue;
                if (switchToOverlay) disk.set("path", overlayPath);
                else disk.set("readonly", true);
            }
        }
        vmStore.save(context);
    }

    private static boolean attachesWritable(@NonNull VMConfig vm, @NonNull String path) {
        var disks = vm.item.opt("disks", null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY)) return false;
        for (var disk : disks.asArray()) {
            if (path.equals(disk.optString("path", ""))
                && !disk.optBoolean("readonly", false)) return true;
        }
        return false;
    }

    /** Names of the given VMs the daemon reports as running; empty on daemon errors. */
    @NonNull
    private List<String> runningNames(@NonNull List<VMConfig> vms) {
        var names = new ArrayList<String>();
        for (var vm : vms) names.add(vm.getName());
        return VmRunningQuery.runningAmong(names);
    }

    private static int chainDepth(@NonNull DiskStore store, @NonNull DiskConfig config) {
        int depth = 0;
        var visited = new HashSet<java.util.UUID>();
        var current = config;
        while (current != null && visited.add(current.getId())) {
            depth++;
            current = store.parentOf(current);
        }
        return depth;
    }

    @NonNull
    private static String detectFormat(@NonNull String path) {
        try {
            var info = ImageUtils.getImageInfo(path);
            var f = info.optString("format", "");
            if (!f.isEmpty()) return f;
        } catch (Exception ignored) {
        }
        return DiskFormat.fromFilename(path).name().toLowerCase(Locale.ROOT);
    }

    private void fail(@Nullable String message) {
        mainLooper.post(() -> new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disk_create_increment)
            .setMessage(message == null ? "?" : message)
            .setPositiveButton(android.R.string.ok, null)
            .show());
    }
}
