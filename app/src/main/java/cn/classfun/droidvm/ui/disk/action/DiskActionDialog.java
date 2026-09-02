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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursor;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursors;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursors.LiveRows;
import cn.classfun.droidvm.ui.disk.tree.CursorPlan;
import cn.classfun.droidvm.ui.disk.tree.CursorPlanText;
import cn.classfun.droidvm.ui.disk.tree.TreeShape;
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
            || id == R.id.menu_disk_reset
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
            confirmDelete(config, null, null);
        } else if (id == R.id.menu_disk_create_increment) {
            // Snapshot-feel path: one name field, instant create. Advanced (size, compression,
            // encryption) falls through to the full create screen in backing mode.
            new DiskOverlayCreateDialog(context, config, onUpdate, () -> {
                var intent = new Intent(context, DiskCreateActivity.class);
                intent.putExtra(DiskCreateActivity.EXTRA_BACKING_ID, config.getId().toString());
                launchActivity(intent);
            }).show();
        } else if (id == R.id.menu_disk_merge) {
            tryMerge(config, null, null);
        } else if (id == R.id.menu_disk_flatten) {
            tryFlatten(config, null);
        } else if (id == R.id.menu_disk_reset) {
            tryReset(config, null, null);
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
     * One family, loaded fresh with everything a tree operation needs to decide and to explain
     * itself: the registry, the VM store, the parent links as a {@link TreeShape}, and every
     * attachment cursor on the family with its VM's run state. Blocking; build off the main
     * thread.
     */
    private final class Family {
        final DiskStore disks = new DiskStore();
        final VMStore vms = new VMStore();
        final DiskConfig self;
        final TreeShape shape;
        final Set<UUID> ids;
        final List<AttachmentCursor> cursors;

        Family(@NonNull DiskConfig config, @Nullable LiveRows live) {
            if (!disks.load(context))
                throw new IllegalStateException(
                    context.getString(R.string.disk_dependency_update_failed));
            var found = disks.findById(config.getId());
            if (found == null)
                throw new IllegalStateException(
                    context.getString(R.string.disk_tree_not_registered));
            self = found;
            vms.load(vms, context); // a missing store just means no VMs yet
            shape = TreeShape.of(disks);
            ids = shape.familyOf(self.getId());
            var inUse = VmRunningQuery.inUseAmong(
                AttachmentCursors.allVmNames(vms, live == null ? null : live.vmName));
            cursors = AttachmentCursors.collect(disks, vms, ids, live, inUse);
        }

        /** Names of VMs that are not stopped and attach anything in this family. */
        @NonNull
        List<String> inUseVmNames() {
            return AttachmentCursors.pinnedVmNames(cursors);
        }
    }

    /**
     * Merge the overlay's changes down into its base ("delete the snapshot, keep the current
     * state"). All conditions are checked and every consequence is stated in ONE confirmation
     * before anything runs; the data merge and its registry/VM follow-up (children re-based
     * onto the base, attachments re-pointed, overlay deleted last) then run unattended in
     * {@code DiskOperationActivity}. Requires the overlay to be its base's only child - commit
     * rewrites the base, which would corrupt sibling overlays - and the whole family's VMs off.
     *
     * @param live        the disk editor's unsaved rows when opened from one; their cursors move
     *                    silently, and the editor's own saved slots are rewritten without being
     *                    announced
     * @param onConfirmed runs (main thread) only once the user confirms, never on cancel
     */
    public void tryMerge(
        @NonNull DiskConfig config, @Nullable LiveRows live, @Nullable Runnable onConfirmed) {
        runOnPool(() -> {
            try {
                var fam = new Family(config, live);
                var self = fam.self;
                var parentId = fam.shape.parentOf(self.getId());
                var parent = parentId == null ? null : fam.disks.findById(parentId);
                if (parent == null) {
                    fail(context.getString(R.string.disk_merge_not_overlay));
                    return;
                }
                if (fam.disks.childrenOf(parent.getId()).size() != 1) {
                    fail(context.getString(R.string.disk_merge_siblings, parent.getName()));
                    return;
                }
                var inUse = fam.inUseVmNames();
                if (!inUse.isEmpty()) {
                    fail(context.getString(R.string.disk_family_vm_running, bulletList(inUse)));
                    return;
                }
                var plan = CursorPlan.reconcile(fam.cursors, List.of(),
                    fam.shape, fam.shape.withMerged(self.getId()));
                int childCount = fam.disks.childrenOf(self.getId()).size();
                var message = new StringBuilder(context.getString(
                    R.string.disk_merge_confirm, self.getName(), parent.getName()));
                if (childCount > 0)
                    message.append(context.getString(
                        R.string.disk_merge_confirm_children, childCount, parent.getName()));
                // Attachments on the base keep their path but get the overlay's content.
                var rewritten = new ArrayList<String>();
                for (var c : fam.cursors)
                    if (c.isAnnounced() && parent.getId().equals(c.nodeId))
                        rewritten.add(CursorPlanText.rewrittenLine(context, c, self.getName()));
                message.append(CursorPlanText.describe(
                    context, plan.announcedChanges(), rewritten));
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
     * Sibling overlays never matter; the family's VMs must be off during the replacement. No
     * attachment moves: the path and the children's backing headers stay valid.
     *
     * @param onConfirmed runs (main thread) only once the user confirms, never on cancel
     */
    public void tryFlatten(@NonNull DiskConfig config, @Nullable Runnable onConfirmed) {
        runOnPool(() -> {
            try {
                var fam = new Family(config, null);
                var self = fam.self;
                var parentId = fam.shape.parentOf(self.getId());
                var parent = parentId == null ? null : fam.disks.findById(parentId);
                if (parent == null) {
                    fail(context.getString(R.string.disk_merge_not_overlay));
                    return;
                }
                var inUse = fam.inUseVmNames();
                if (!inUse.isEmpty()) {
                    fail(context.getString(R.string.disk_family_vm_running, bulletList(inUse)));
                    return;
                }
                var message = context.getString(
                    R.string.disk_flatten_confirm, self.getName(), parent.getName());
                mainLooper.post(() -> new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.disk_flatten)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        try {
                            var obj = new JSONObject();
                            obj.put("action", "flatten");
                            context.startActivity(createIntent(context, config.getId(), obj));
                            if (onConfirmed != null) onConfirmed.run();
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

    /**
     * Throw away everything written into a leaf overlay and start it over as a fresh, empty
     * overlay of the same base ("roll back to the snapshot"). qemu-img has no command for that,
     * so the overlay is recreated: a new header-only image is written beside it, carrying the
     * same backing link, virtual size, cluster size and compression type, and then renamed over
     * the original - an atomic swap, so a failure leaves the old file untouched. The path never
     * changes, so no VM slot moves and nothing is announced beyond "its content resets".
     *
     * <p>Only for a writable leaf: an overlay with overlays of its own is their base, and an
     * encrypted one cannot be recreated without its key. The VMs attaching it must be off.
     *
     * @param onConfirmed runs (main thread) after the swap succeeded, never on cancel or failure
     */
    public void tryReset(
        @NonNull DiskConfig config, @Nullable LiveRows live, @Nullable Runnable onConfirmed) {
        runOnPool(() -> {
            try {
                var fam = new Family(config, live);
                var self = fam.self;
                var parentId = fam.shape.parentOf(self.getId());
                var parent = parentId == null ? null : fam.disks.findById(parentId);
                if (parent == null) {
                    fail(context.getString(R.string.disk_merge_not_overlay));
                    return;
                }
                if (fam.shape.hasChildren(self.getId())) {
                    fail(context.getString(R.string.disk_reset_has_children, self.getName()));
                    return;
                }
                var pinned = new ArrayList<AttachmentCursor>();
                var rewritten = new ArrayList<String>();
                for (var c : fam.cursors) {
                    if (!self.getId().equals(c.nodeId)) continue;
                    if (c.pinned) pinned.add(c);
                    if (c.isAnnounced())
                        rewritten.add(CursorPlanText.rewrittenLine(context, c, parent.getName()));
                }
                if (!pinned.isEmpty()) {
                    fail(CursorPlanText.pinnedMessage(context, pinned));
                    return;
                }
                var info = ImageUtils.getImageInfo(self.getFullPath());
                if (info.optBoolean("encrypted", false)) {
                    fail(context.getString(R.string.disk_reset_encrypted, self.getName()));
                    return;
                }
                var message = context.getString(
                    R.string.disk_reset_confirm, self.getName(), parent.getName())
                    + CursorPlanText.describe(context, List.of(), rewritten);
                mainLooper.post(() -> new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.disk_reset)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) ->
                        runOnPool(() -> resetOverlay(self, parent, info, onConfirmed)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            } catch (Exception e) {
                Log.w(TAG, "reset pre-checks failed", e);
                fail(String.valueOf(e.getMessage()));
            }
        });
    }

    /** Recreate {@code self} empty on {@code parent} beside itself, then swap it into place. */
    private void resetOverlay(
        @NonNull DiskConfig self,
        @NonNull DiskConfig parent,
        @NonNull JSONObject info,
        @Nullable Runnable onConfirmed
    ) {
        var path = self.getFullPath();
        var tmp = path + ".reset.tmp";
        try {
            var parentPath = parent.getFullPath();
            String backingFormat;
            try {
                backingFormat = ImageUtils.getImageInfo(parentPath).optString("format", "qcow2");
            } catch (Exception e) {
                backingFormat = "qcow2";
            }
            var args = new ArrayList<String>(List.of(
                getPrebuiltBinaryPath("qemu-img"), "create",
                "--format", "qcow2",
                "--backing", parentPath,
                "--backing-format", backingFormat));
            // Keep the image's own layout choices so the reset overlay behaves like the old one.
            var opts = new ArrayList<String>();
            long cluster = info.optLong("cluster-size", 0);
            if (cluster > 0) opts.add("cluster_size=" + cluster);
            var specific = info.optJSONObject("format-specific");
            var data = specific == null ? null : specific.optJSONObject("data");
            var compression = data == null ? "" : data.optString("compression-type", "");
            if (!compression.isEmpty()) opts.add("compression_type=" + compression);
            if (!opts.isEmpty()) {
                args.add("-o");
                args.add(String.join(",", opts));
            }
            args.add(tmp);
            long size = info.optLong("virtual-size", 0);
            if (size > 0) args.add(String.valueOf(size));
            var result = runList(args.toArray(new String[0]));
            if (!result.isSuccess()) {
                result.printLog(TAG);
                runList("rm", "-f", tmp);
                fail(context.getString(R.string.disk_reset_failed));
                return;
            }
            if (!new File(tmp).renameTo(new File(path))) {
                var moved = runList("mv", "-f", tmp, path);
                if (!moved.isSuccess()) {
                    moved.printLog(TAG);
                    runList("rm", "-f", tmp);
                    fail(context.getString(R.string.disk_reset_failed));
                    return;
                }
            }
            mainLooper.post(() -> {
                Toast.makeText(context,
                    context.getString(R.string.disk_reset_done, self.getName()),
                    LENGTH_SHORT).show();
                if (onUpdate != null) onUpdate.run();
                if (onConfirmed != null) onConfirmed.run();
            });
        } catch (Exception e) {
            Log.e(TAG, "overlay reset failed", e);
            runList("rm", "-f", tmp);
            fail(context.getString(R.string.disk_reset_failed));
        }
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

    /**
     * Delete a disk and everything overlaying it. An overlay holds only differences against its
     * base, so a base cannot go without taking its descendants with it - deleting is therefore
     * always a whole-subtree operation (unlike merge, which preserves the data by writing it
     * down into the base first and can re-link the survivors). The confirmation lists every disk
     * that will go, says so as "delete the entire tree" when the target is a family root, and
     * states where other VMs' attachments move (to the nearest surviving base, read-only when
     * that base still has overlays or another VM holds it; removed when nothing is left).
     *
     * @param live        see {@link #tryMerge}
     * @param onConfirmed runs (main thread) after the registry is written, never on cancel
     */
    public void confirmDelete(
        @NonNull DiskConfig config, @Nullable LiveRows live, @Nullable Runnable onConfirmed) {
        runOnPool(() -> {
            try {
                var fam = new Family(config, live);
                var self = fam.self;
                // BFS order, self first; deletion goes leaves-first, i.e. reversed.
                var subtree = new ArrayList<>(fam.shape.subtreeOf(self.getId()));
                var after = fam.shape.without(fam.shape.subtreeOf(self.getId()));
                var plan = CursorPlan.reconcile(fam.cursors, List.of(), fam.shape, after);
                if (plan.isRefused()) {
                    fail(CursorPlanText.pinnedMessage(context, plan.refused));
                    return;
                }
                boolean isRoot = fam.shape.parentOf(self.getId()) == null;
                var names = new ArrayList<String>();
                var paths = new ArrayList<String>();
                for (var id : subtree) {
                    names.add(String.valueOf(fam.shape.nameOf(id)));
                    paths.add(String.valueOf(fam.shape.pathOf(id)));
                }
                mainLooper.post(() ->
                    showDeleteDialog(subtree, names, paths, isRoot, plan, onConfirmed));
            } catch (Exception e) {
                Log.w(TAG, "delete pre-checks failed", e);
                fail(String.valueOf(e.getMessage()));
            }
        });
    }

    private void showDeleteDialog(
        @NonNull List<UUID> subtree,
        @NonNull List<String> names,
        @NonNull List<String> paths,
        boolean isRoot,
        @NonNull CursorPlan plan,
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
            message.append(context.getString(
                isRoot ? R.string.disk_delete_tree_message
                    : R.string.disk_delete_subtree_message, bulletList(names)));
        } else {
            message.append(context.getString(R.string.disk_delete_confirm));
        }
        message.append(CursorPlanText.describe(context, plan.announcedChanges()));
        DialogInterface.OnClickListener onclick = (d, w) -> {
            boolean isChecked = checkBox.isChecked();
            runOnPool(() -> {
                var store = new DiskStore();
                if (!store.load(context) || !DiskDependencyUpdater.applyPlan(context, plan)) {
                    fail(context.getString(R.string.disk_dependency_update_failed));
                    return;
                }
                // Registry first, leaves first. Files stay present until both VM references and
                // the registry have been saved, so an I/O failure cannot create dangling slots.
                for (int i = subtree.size() - 1; i >= 0; i--)
                    store.removeById(subtree.get(i));
                if (!store.save(context)) {
                    fail(context.getString(R.string.disk_dependency_update_failed));
                    return;
                }
                if (isChecked) {
                    for (int i = paths.size() - 1; i >= 0; i--)
                        runList("rm", "-f", paths.get(i));
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
                : names.get(0))
            .setMessage(message)
            .setView(layout)
            .setPositiveButton(R.string.vm_delete, onclick)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
