// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import static cn.classfun.droidvm.lib.utils.StringUtils.bulletList;

import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.dirname;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.createIntent;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.IdRes;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.disk.tree.DiskTree;
import cn.classfun.droidvm.ui.vm.VmRunningQuery;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.agent.password.ChangePasswordActivity;
import cn.classfun.droidvm.ui.disk.create.DiskCompress;
import cn.classfun.droidvm.ui.disk.create.DiskCreateActivity;
import cn.classfun.droidvm.ui.disk.download.ImportURLActivity;
import cn.classfun.droidvm.ui.disk.images.ImportImagesActivity;
import cn.classfun.droidvm.ui.disk.lxc.ImportLxcImagesActivity;
import cn.classfun.droidvm.ui.disk.operation.OptimizeCompression;

public final class DiskActionDialog {
    private final static String TAG = "DiskActionDialog";
    private final Handler mainLooper = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> activityLauncher;
    private final Context context;
    private final Runnable onUpdate;
    private final Runnable filePicker;

    public DiskActionDialog(
        @NonNull Context context,
        @Nullable Runnable onUpdate,
        @Nullable Runnable filePicker
    ) {
        this(context, onUpdate, filePicker, null);
    }

    public DiskActionDialog(
        @NonNull Context context,
        @Nullable Runnable onUpdate,
        @Nullable Runnable filePicker,
        @Nullable ActivityResultLauncher<Intent> activityLauncher
    ) {
        this.context = context;
        this.onUpdate = onUpdate;
        this.filePicker = filePicker;
        this.activityLauncher = activityLauncher;
    }

    /** Source menu for the disk list and the disk-info action grid derived from it. */
    @MenuRes
    public static int getMenuResId(@NonNull DiskConfig config) {
        if (!DiskConfig.supportsExtraOperations(config.getFormat()))
            return R.menu.menu_disk_actions_simple;
        if (config.getParentId() != null)
            return R.menu.menu_disk_actions_overlay;
        return R.menu.menu_disk_actions;
    }

    /** Actions that rewrite bytes in the selected image and are unsafe while it has overlays. */
    public static boolean modifiesDiskContent(@IdRes int id) {
        return id == R.id.menu_disk_resize
            || id == R.id.menu_disk_convert
            || id == R.id.menu_disk_optimize
            || id == R.id.menu_disk_change_password;
    }

    public boolean diskMenuOnClick(@NonNull DiskConfig config, @IdRes int id) {
        if (!isDiskAction(id)) return false;
        Runnable action = () -> performDiskAction(config, id);
        if (modifiesDiskContent(id))
            guardUnlocked(config, action);
        else
            action.run();
        return true;
    }

    private static boolean isDiskAction(@IdRes int id) {
        return modifiesDiskContent(id)
            || id == R.id.menu_disk_delete
            || id == R.id.menu_disk_create_increment
            || id == R.id.menu_disk_merge
            || id == R.id.menu_disk_flatten
            || id == R.id.menu_disk_show_info
            || id == R.id.menu_disk_clone;
    }

    private void performDiskAction(@NonNull DiskConfig config, @IdRes int id) {
        if (id == R.id.menu_disk_resize) {
            new DiskResizeDialog(context, config);
        } else if (id == R.id.menu_disk_convert) {
            new DiskSetFormatDialog(context, config).show();
        } else if (id == R.id.menu_disk_optimize) {
            tryOptimize(config);
        } else if (id == R.id.menu_disk_delete) {
            confirmDelete(config);
        } else if (id == R.id.menu_disk_create_increment) {
            // Snapshot-feel path: one name field, instant create. Advanced (size, compression,
            // encryption) falls through to the full create screen in backing mode.
            new DiskOverlayCreateDialog(context, config, onUpdate, () -> {
                var intent = new Intent(context, DiskCreateActivity.class);
                intent.putExtra(DiskCreateActivity.EXTRA_BACKING_ID, config.getId().toString());
                launchActivity(intent);
            }).show();
        } else if (id == R.id.menu_disk_merge) {
            tryMerge(config);
        } else if (id == R.id.menu_disk_flatten) {
            tryFlatten(config);
        } else if (id == R.id.menu_disk_show_info) {
            showMoreInfo(config);
        } else if (id == R.id.menu_disk_clone) {
            new DiskCloneDialog(context, config).show();
        } else if (id == R.id.menu_disk_change_password) {
            var intent = ChangePasswordActivity.createIntent(context, config.getId());
            launchActivity(intent);
        }
    }

    /**
     * Merge the overlay's changes down into its base ("delete the snapshot, keep the current
     * state"). All conditions are checked and every consequence is stated in ONE confirmation
     * before anything runs; the data merge and its registry/VM follow-up (children re-based
     * onto the base, attachments re-pointed, overlay deleted last) then run unattended in
     * {@code DiskOperationActivity}. Requires the overlay to be its base's only child - commit
     * rewrites the base, which would corrupt sibling overlays - and the whole family's VMs off.
     */
    public void tryMerge(@NonNull DiskConfig config) {
        tryMerge(config, null);
    }

    /**
     * @param extraNote appended to the confirmation - the caller's own consequences (e.g. the
     *                  VM row being edited will re-point), so the user still sees exactly one
     *                  question before anything runs
     */
    public void tryMerge(@NonNull DiskConfig config, @Nullable String extraNote) {
        tryMerge(config, extraNote, null);
    }

    /** @param onConfirmed runs (main thread) only once the user confirms, never on cancel. */
    public void tryMerge(
        @NonNull DiskConfig config, @Nullable String extraNote, @Nullable Runnable onConfirmed) {
        runOnPool(() -> {
            try {
                var store = new DiskStore();
                store.load(context);
                var self = store.findById(config.getId());
                var parent = self == null ? null : store.parentOf(self);
                if (parent == null) {
                    fail(context.getString(R.string.disk_merge_not_overlay));
                    return;
                }
                if (store.childrenOf(parent.getId()).size() != 1) {
                    fail(context.getString(R.string.disk_merge_siblings, parent.getName()));
                    return;
                }
                var runningNames = runningFamilyVms(store, self);
                if (!runningNames.isEmpty()) {
                    fail(context.getString(R.string.disk_family_vm_running,
                        bulletList(runningNames)));
                    return;
                }
                int childCount = store.childrenOf(self.getId()).size();
                var repoints = vmsAttaching(self.getFullPath());
                var message = new StringBuilder(context.getString(
                    R.string.disk_merge_confirm, self.getName(), parent.getName()));
                if (childCount > 0)
                    message.append(context.getString(
                        R.string.disk_merge_confirm_children, childCount, parent.getName()));
                if (!repoints.isEmpty())
                    message.append(context.getString(
                        R.string.disk_merge_confirm_vms,
                        bulletList(repoints), parent.getName()));
                if (extraNote != null) message.append(extraNote);
                mainLooper.post(() -> new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.disk_merge)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        try {
                            var obj = new JSONObject();
                            obj.put("action", "commit");
                            context.startActivity(createIntent(context, config.getId(), obj));
                            if (onConfirmed != null) onConfirmed.run();
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to start commit", e);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            } catch (Exception e) {
                Log.w(TAG, "merge pre-checks failed", e);
                fail(String.valueOf(e.getMessage()));
            }
        });
    }

    /**
     * Make the overlay standalone by copying its complete backing-chain view to a temporary image
     * and replacing the overlay only after that copy succeeds ("take the branch with you").
     * Sibling overlays never matter; the family's VMs must be off during the replacement.
     */
    public void tryFlatten(@NonNull DiskConfig config) {
        tryFlatten(config, null);
    }

    /** @param extraNote appended to the confirmation; see {@link #tryMerge(DiskConfig, String)}. */
    public void tryFlatten(@NonNull DiskConfig config, @Nullable String extraNote) {
        runOnPool(() -> {
            try {
                var store = new DiskStore();
                store.load(context);
                var self = store.findById(config.getId());
                var parent = self == null ? null : store.parentOf(self);
                if (parent == null) {
                    fail(context.getString(R.string.disk_merge_not_overlay));
                    return;
                }
                var runningNames = runningFamilyVms(store, self);
                if (!runningNames.isEmpty()) {
                    fail(context.getString(R.string.disk_family_vm_running,
                        bulletList(runningNames)));
                    return;
                }
                var message = context.getString(
                    R.string.disk_flatten_confirm, self.getName(), parent.getName())
                    + (extraNote == null ? "" : extraNote);
                mainLooper.post(() -> new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.disk_flatten)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        try {
                            var obj = new JSONObject();
                            obj.put("action", "flatten");
                            context.startActivity(createIntent(context, config.getId(), obj));
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to start flatten", e);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            } catch (Exception e) {
                Log.w(TAG, "flatten pre-checks failed", e);
                fail(String.valueOf(e.getMessage()));
            }
        });
    }

    /** Running VMs attaching any image in {@code config}'s whole family tree. Blocking. */
    @NonNull
    private List<String> runningFamilyVms(@NonNull DiskStore store, @NonNull DiskConfig config) {
        var paths = new HashSet<String>();
        var queue = new ArrayDeque<UUID>();
        queue.add(DiskTree.rootOf(store, config.getId()));
        var seen = new HashSet<UUID>();
        while (!queue.isEmpty()) {
            var id = queue.poll();
            if (!seen.add(id)) continue;
            var cfg = store.findById(id);
            if (cfg == null) continue;
            paths.add(cfg.getFullPath());
            for (var child : store.childrenOf(id)) queue.add(child.getId());
        }
        var candidates = new ArrayList<String>();
        try {
            var vmStore = new VMStore();
            if (vmStore.load(vmStore, context)) {
                for (int i = 0; i < vmStore.size(); i++) {
                    var vm = vmStore.get(i);
                    if (attachesAny(vm, paths)) candidates.add(vm.getName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "family VM scan failed", e);
        }
        return VmRunningQuery.runningAmong(candidates);
    }

    /** {@code config} followed by all its descendants, parents before children. */
    private static void collectSubtree(
        @NonNull DiskStore store, @NonNull DiskConfig config, @NonNull List<DiskConfig> out) {
        for (var seen : out)
            if (seen.getId().equals(config.getId())) return; // cycle guard
        out.add(config);
        for (var child : store.childrenOf(config.getId()))
            collectSubtree(store, child, out);
    }

    /** Running VMs attaching any of {@code paths}. Blocking. */
    @NonNull
    private List<String> runningVmsAttaching(@NonNull java.util.Set<String> paths) {
        var candidates = new ArrayList<String>();
        try {
            var vmStore = new VMStore();
            if (vmStore.load(vmStore, context)) {
                for (int i = 0; i < vmStore.size(); i++) {
                    var vm = vmStore.get(i);
                    if (attachesAny(vm, paths)) candidates.add(vm.getName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "attachment scan failed", e);
        }
        return VmRunningQuery.runningAmong(candidates);
    }

    /** Names of VMs (any attach mode) holding {@code path}. */
    @NonNull
    private List<String> vmsAttaching(@NonNull String path) {
        var out = new ArrayList<String>();
        try {
            var vmStore = new VMStore();
            if (vmStore.load(vmStore, context)) {
                for (int i = 0; i < vmStore.size(); i++) {
                    var vm = vmStore.get(i);
                    if (attachesAny(vm, java.util.Set.of(path))) out.add(vm.getName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "attachment scan failed", e);
        }
        return out;
    }

    private static boolean attachesAny(
        @NonNull VMConfig vm, @NonNull java.util.Set<String> paths) {
        var disks = vm.item.opt("disks", null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY)) return false;
        for (var disk : disks.asArray())
            if (paths.contains(disk.optString("path", ""))) return true;
        return false;
    }

    private void fail(@Nullable String message) {
        mainLooper.post(() -> new MaterialAlertDialogBuilder(context)
            .setMessage(message == null ? "?" : message)
            .setPositiveButton(android.R.string.ok, null)
            .show());
    }

    /**
     * Rewriting a disk that other images overlay would shift the ground under those overlays.
     * Tree-aware operations (create, merge, flatten and delete) deliberately remain available;
     * only byte-mutating actions pass through this guard. The registry read happens off the main
     * thread and fails closed.
     */
    private void guardUnlocked(@NonNull DiskConfig config, @NonNull Runnable action) {
        runOnPool(() -> {
            int children = -1;
            try {
                var store = new DiskStore();
                if (store.load(context))
                    children = store.childrenOf(config.getId()).size();
            } catch (Exception e) {
                Log.w(TAG, "Failed to check disk children", e);
            }
            final int n = children;
            mainLooper.post(() -> {
                if (n == 0) {
                    action.run();
                    return;
                }
                if (n < 0) {
                    Toast.makeText(context, R.string.disk_info_load_failed, LENGTH_SHORT).show();
                    return;
                }
                new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.disk_locked_title)
                    .setMessage(context.getString(
                        R.string.disk_locked_message, config.getName(), n))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            });
        });
    }

    public void tryOptimize(@NonNull DiskConfig config) {
        // Target compression comes from the preferred-compression setting (or its ask prompt).
        OptimizeCompression.resolve(context, () -> {}, compress -> tryOptimize(config, compress));
    }

    private void tryOptimize(@NonNull DiskConfig config, @NonNull DiskCompress compress) {
        Consumer<JSONObject> invoke = obj -> {
            try {
                var intent = createIntent(context, config.getId(), obj);
                context.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start optimize activity", e);
            }
        };
        runOnPool(() -> {
            var obj = new JSONObject();
            try {
                var info = ImageUtils.getImageInfo(config.getFullPath());
                obj.put("action", "convert");
                obj.put("compress", compress.value());
                obj.put("format", info.getString("format"));
                if (info.has("backing-filename"))
                    obj.put("backing_path", info.getString("backing-filename"));
            } catch (Exception e) {
                Log.w(TAG, "Failed to optimize", e);
                mainLooper.post(() ->
                    Toast.makeText(context, e.getMessage(), LENGTH_SHORT).show());
                return;
            }
            if (!obj.has("backing_path")) {
                invoke.accept(obj);
                return;
            }
            mainLooper.post(() -> new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.disk_optimize_backing_title)
                .setMessage(R.string.disk_optimize_backing_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.disk_optimize_backing_btn_keep, (d, w) ->
                    invoke.accept(obj))
                .setNeutralButton(R.string.disk_optimize_backing_btn_flatten, (d, w) -> {
                    obj.remove("backing_path");
                    invoke.accept(obj);
                })
                .show());
        });
    }

    private void showMoreInfo(@NonNull DiskConfig config) {
        runOnPool(() -> {
            final String infos;
            try {
                var result = runList(
                    getPrebuiltBinaryPath("qemu-img"),
                    "info", config.getFullPath()
                );
                if (!result.isSuccess()) {
                    result.printLog("qemu-img");
                    throw new RuntimeException(fmt("qemu-img failed: %d", result.getCode()));
                }
                infos = String.join("\n", result.getOutString()).trim();
            } catch (Exception e) {
                Log.w(TAG, "Failed to read image info", e);
                mainLooper.post(() ->
                    Toast.makeText(context, e.getMessage(), LENGTH_SHORT).show());
                return;
            }
            mainLooper.post(() -> {
                var inf = LayoutInflater.from(context);
                var view = inf.inflate(R.layout.dialog_logs, null);
                TextView tvLog = view.findViewById(R.id.tv_log);
                tvLog.setText(infos);
                new MaterialAlertDialogBuilder(context)
                    .setTitle(config.getName())
                    .setView(view)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            });
        });
    }

    public void showImportDialog() {
        MenuDialogBuilder.showSimple(
            context,
            R.string.disk_add_title,
            R.menu.menu_disk_add,
            this::onImportItemSelected
        );
    }

    public boolean onImportItemSelected(@NonNull MenuItem item) {
        var id = item.getItemId();
        if (id == R.id.menu_disk_add_import) {
            if (filePicker != null) filePicker.run();
            return false;
        }
        Class<? extends Activity> target;
        if (id == R.id.menu_disk_add_url) {
            target = ImportURLActivity.class;
        } else if (id == R.id.menu_disk_add_images) {
            target = ImportImagesActivity.class;
        } else if (id == R.id.menu_disk_add_lxc) {
            target = ImportLxcImagesActivity.class;
        } else if (id == R.id.menu_disk_add_create) {
            target = DiskCreateActivity.class;
        } else return false;
        launchActivity(new Intent(context, target));
        return true;
    }

    public DiskConfig onFileImported(Uri uri) {
        if (uri == null) return null;
        var path = resolveUriPath(context, uri);
        if (path == null || path.isEmpty()) {
            Toast.makeText(context, R.string.disk_create_error_folder_invalid, LENGTH_SHORT).show();
            return null;
        }
        if (!path.startsWith("/") || !path.contains("/")) {
            Toast.makeText(context, R.string.disk_create_error_folder_invalid, LENGTH_SHORT).show();
            return null;
        }
        var fileName = basename(path);
        var folder = dirname(path);
        var config = new DiskConfig();
        config.setName(fileName);
        config.item.set("folder", folder);
        var store = new DiskStore();
        runOnPool(() -> {
            try {
                store.load(context);
                if (store.findByName(fileName) != null) {
                    mainLooper.post(() -> Toast.makeText(
                        context,
                        R.string.disk_create_error_exists,
                        LENGTH_SHORT
                    ).show());
                    return;
                }
                store.add(config);
                store.save(context);
            } catch (Exception ignored) {
                mainLooper.post(() -> Toast.makeText(
                    context,
                    R.string.disk_create_error_folder_invalid,
                    LENGTH_SHORT
                ).show());
                return;
            }
            mainLooper.post(() -> {
                var str = context.getString(R.string.disk_create_success, fileName);
                Toast.makeText(context, str, LENGTH_SHORT).show();
            });
            if (this.onUpdate != null)
                this.onUpdate.run();
            // Resolve the imported image's backing chain: link/offer-to-import parents,
            // absolutize relative backing paths. Prompts (if any) come as a single dialog.
            BackingChainLinker.link(context, config.getId(), this.onUpdate);
        });
        return config;
    }

    private void launchActivity(@NonNull Intent intent) {
        if (activityLauncher != null)
            activityLauncher.launch(intent);
        else
            context.startActivity(intent);
    }

    public void confirmDelete(@NonNull DiskConfig config) {
        confirmDelete(config, null);
    }

    /** @param extraNote appended to the confirmation; see {@link #tryMerge(DiskConfig, String)}. */
    public void confirmDelete(@NonNull DiskConfig config, @Nullable String extraNote) {
        confirmDelete(config, extraNote, null);
    }

    /**
     * Delete a disk and everything overlaying it. An overlay holds only differences against its
     * base, so a base cannot go without taking its descendants with it - deleting is therefore
     * always a whole-subtree operation (unlike merge, which preserves the data by writing it
     * down into the base first and can re-link the survivors). The confirmation lists every disk
     * that will go, and says so as "delete the entire tree" when the target is a family root.
     *
     * @param onConfirmed runs (main thread) only once the user confirms, never on cancel.
     */
    public void confirmDelete(
        @NonNull DiskConfig config, @Nullable String extraNote, @Nullable Runnable onConfirmed) {
        runOnPool(() -> {
            var store = new DiskStore();
            if (!store.load(context)) {
                fail(context.getString(R.string.disk_dependency_update_failed));
                return;
            }
            var subtree = new ArrayList<DiskConfig>();
            var self = store.findById(config.getId());
            collectSubtree(store, self == null ? config : self, subtree);
            var parent = self == null ? null : store.parentOf(self);
            boolean isRoot = parent == null;
            var replacementPath = parent == null ? null : parent.getFullPath();
            var paths = new HashSet<String>();
            for (var cfg : subtree) paths.add(cfg.getFullPath());
            var runningNames = runningVmsAttaching(paths);
            if (!runningNames.isEmpty()) {
                fail(context.getString(R.string.disk_family_vm_running,
                    bulletList(runningNames)));
                return;
            }
            mainLooper.post(() ->
                showDeleteDialog(
                    subtree, isRoot, paths, replacementPath, extraNote, onConfirmed));
        });
    }

    private void showDeleteDialog(
        @NonNull List<DiskConfig> subtree,
        boolean isRoot,
        @NonNull java.util.Set<String> subtreePaths,
        @Nullable String replacementPath,
        @Nullable String extraNote,
        @Nullable Runnable onConfirmed
    ) {
        var layout = new LinearLayout(context);
        var checkBox = new CheckBox(context);
        checkBox.setText(R.string.disk_delete_file);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, 0, pad, 0);
        layout.addView(checkBox);
        var message = new StringBuilder();
        if (subtree.size() > 1) {
            var names = new StringBuilder();
            for (var cfg : subtree) names.append("\n- ").append(cfg.getName());
            message.append(context.getString(
                isRoot ? R.string.disk_delete_tree_message
                    : R.string.disk_delete_subtree_message, names.toString()));
        } else {
            message.append(context.getString(R.string.disk_delete_confirm));
        }
        if (extraNote != null) message.append(extraNote);
        DialogInterface.OnClickListener onclick = (d, w) -> {
            boolean isChecked = checkBox.isChecked();
            runOnPool(() -> {
                var store = new DiskStore();
                if (!store.load(context)
                    || !DiskDependencyUpdater.redirectVmDisks(
                        context, subtreePaths, replacementPath)) {
                    fail(context.getString(R.string.disk_dependency_update_failed));
                    return;
                }
                // Registry first, leaves first. Files stay present until both VM references and
                // the registry have been saved, so an I/O failure cannot create dangling slots.
                for (int i = subtree.size() - 1; i >= 0; i--) {
                    var cfg = subtree.get(i);
                    store.removeById(cfg.getId());
                }
                if (!store.save(context)) {
                    fail(context.getString(R.string.disk_dependency_update_failed));
                    return;
                }
                if (isChecked) {
                    for (int i = subtree.size() - 1; i >= 0; i--)
                        runList("rm", "-f", subtree.get(i).getFullPath());
                }
                if (this.onUpdate != null)
                    mainLooper.post(this.onUpdate);
                // After the registry is written, so a listener re-reading it (e.g. to redo the
                // lock state of a disk that just lost its last overlay) sees the new truth.
                if (onConfirmed != null) mainLooper.post(onConfirmed);
            });
        };
        new MaterialAlertDialogBuilder(context)
            .setTitle(subtree.size() > 1 && isRoot
                ? context.getString(R.string.disk_delete_tree_title)
                : subtree.get(0).getName())
            .setMessage(message)
            .setView(layout)
            .setPositiveButton(R.string.vm_delete, onclick)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
