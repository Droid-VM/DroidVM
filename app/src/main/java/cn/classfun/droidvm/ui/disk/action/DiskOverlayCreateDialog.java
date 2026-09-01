// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.bulletList;
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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursors;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursors.LiveRows;
import cn.classfun.droidvm.ui.disk.tree.CursorPlan;
import cn.classfun.droidvm.ui.disk.tree.DiskTree;
import cn.classfun.droidvm.ui.disk.tree.TreeShape;
import cn.classfun.droidvm.ui.vm.VmRunningQuery;

/**
 * Snapshot-feel overlay creation: one name field, instant {@code qemu-img create} (an overlay is
 * just a header), the base becomes a locked parent. Every decision is collected BEFORE anything
 * executes: the {@link CursorPlan} says which VM slots would follow the new overlay down (the
 * base's writable attachments - "took a snapshot, keep going") and the user picks, per the
 * whole batch, whether they do or flip to read-only instead; a base held writable by a VM that
 * isn't stopped blocks creation outright, since its writes would shift the base underneath the
 * overlay. After confirmation the create, registry link and VM updates run unattended.
 */
public final class DiskOverlayCreateDialog {
    private static final String TAG = "DiskOverlayCreate";
    /**
     * A name this dialog generated before, at the very end: {@code -ov-yyMMdd-HHmmss}, the older
     * {@code -ov-yyyyMMdd-HHmm}, either with an optional {@code -N} collision bump.
     */
    private static final Pattern OVERLAY_SUFFIX =
        Pattern.compile("-ov-(\\d{8}-\\d{4}|\\d{6}-\\d{6})(-\\d+)?$");
    private final Handler mainLooper = new Handler(Looper.getMainLooper());
    private final Context context;
    private final DiskConfig parent;
    @Nullable
    private final Runnable onUpdate;
    @Nullable
    private final Runnable onAdvanced;
    @Nullable
    private LiveRows live;

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
     * The disk editor's unsaved rows when opened from one. Their cursors are not written here
     * (the editor applies them when its panel closes) and the editor's own saved slots are
     * rewritten without being announced - the rows on screen stand in for them.
     */
    @NonNull
    public DiskOverlayCreateDialog setLiveRows(@Nullable LiveRows live) {
        this.live = live;
        return this;
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
     * {@code <base>-ov-<yyMMdd-HHmmss>}, to the second so two overlays taken in quick
     * succession don't collide, and bumped with {@code -2, -3, ...} if a file of that name is
     * already there. Stacking overlays replaces a trailing stamp instead of growing another one -
     * a chain would otherwise read {@code disk-ov-260101-000000-ov-260102-000000-...}.
     */
    @NonNull
    private String defaultName() {
        var base = parent.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = OVERLAY_SUFFIX.matcher(base).replaceFirst("");
        var stamp = new SimpleDateFormat("yyMMdd-HHmmss", Locale.ROOT).format(new Date());
        var folder = parent.item.optString("folder", "");
        var candidate = fmt("%s-ov-%s", base, stamp);
        var name = candidate;
        for (int n = 2; new File(pathJoin(folder, name + ".qcow2")).exists(); n++)
            name = fmt("%s-%d", candidate, n);
        return name;
    }

    // Phase 1, off the main thread: validate, plan where the base's attachments go.
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
                var shape = TreeShape.of(store);
                var family = shape.familyOf(parent.getId());
                var inUse = VmRunningQuery.inUseAmong(
                    AttachmentCursors.allVmNames(vmStore, live == null ? null : live.vmName));
                var cursors = AttachmentCursors.collect(store, vmStore, family, live, inUse);
                // The overlay's id isn't known yet; the plan only needs its path and parent.
                var after = shape.withChild(UUID.randomUUID(), overlayPath, name, parent.getId());
                var plan = CursorPlan.reconcile(cursors, List.of(), shape, after);
                if (plan.isRefused()) {
                    fail(context.getString(R.string.disk_overlay_vm_running,
                        bulletList(AttachmentCursors.vmNames(plan.refused))));
                    return;
                }
                var announced = plan.announcedChanges();
                if (announced.isEmpty()) {
                    execute(name, overlayPath, plan, true);
                    return;
                }
                mainLooper.post(() -> askVmChoice(name, overlayPath, plan));
            } catch (Exception e) {
                Log.w(TAG, "overlay pre-checks failed", e);
                fail(String.valueOf(e.getMessage()));
            }
        });
    }

    // Phase 2, main thread: the one decision point - other VMs' writable attachments follow the
    // overlay (default) or stay on the base read-only. Everything after runs unattended.
    private void askVmChoice(
        @NonNull String name, @NonNull String overlayPath, @NonNull CursorPlan plan) {
        var lines = new ArrayList<String>();
        for (var c : plan.announcedChanges())
            lines.add(fmt("%s (#%d)", c.from.vmName, c.from.slot + 1));
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disk_overlay_vm_choice_title)
            .setMessage(context.getString(
                R.string.disk_overlay_vm_choice_message, parent.getName(), bulletList(lines)))
            .setPositiveButton(R.string.disk_overlay_vm_switch, (d, w) ->
                runOnPool(() -> execute(name, overlayPath, plan, true)))
            .setNeutralButton(R.string.disk_overlay_vm_readonly, (d, w) ->
                runOnPool(() -> execute(name, overlayPath, plan, false)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // Phase 3, off the main thread, no further interaction: create, register, update VMs.
    private void execute(
        @NonNull String name,
        @NonNull String overlayPath,
        @NonNull CursorPlan plan,
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
            if (!DiskDependencyUpdater.applyPlan(context, vmPlan(plan, switchVmsToOverlay)))
                Log.e(TAG, "overlay created but VM attachments could not be updated");
            mainLooper.post(() -> {
                Toast.makeText(context,
                    context.getString(R.string.disk_overlay_created, name),
                    LENGTH_SHORT).show();
                if (onUpdate != null) onUpdate.run();
            });
        } catch (Exception e) {
            Log.e(TAG, "overlay creation failed", e);
            fail(String.valueOf(e.getMessage()));
        }
    }

    /**
     * The plan as the user chose it: with "make read-only", the announced slots stay on the base
     * read-only instead of following the overlay. The editor's own saved slots (shadows) always
     * follow - the rows on screen do, and a discarded edit must leave them consistent with what
     * the user saw.
     */
    @NonNull
    private static CursorPlan vmPlan(@NonNull CursorPlan plan, boolean switchVmsToOverlay) {
        if (switchVmsToOverlay) return plan;
        var alt = CursorPlan.reconcile(List.of(), List.of(), TreeShape.empty(), TreeShape.empty());
        for (var c : plan.changes) {
            if (c.from.isAnnounced() && c.moved())
                alt.changes.add(new CursorPlan.Change(
                    c.from, c.from.at(c.from.nodeId, c.from.path, true)));
            else
                alt.changes.add(c);
        }
        return alt;
    }

    private static int chainDepth(@NonNull DiskStore store, @NonNull DiskConfig config) {
        int depth = 0;
        var visited = new HashSet<UUID>();
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
