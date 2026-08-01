// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.info.snapshot;

import static android.content.DialogInterface.BUTTON_POSITIVE;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static java.text.DateFormat.MEDIUM;
import static java.text.DateFormat.SHORT;
import static java.text.DateFormat.getDateTimeInstance;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.disk.info.DiskInfoActivity;
import cn.classfun.droidvm.ui.disk.info.base.DiskInfoBaseTab;

public final class DiskInfoSnapshotTab extends DiskInfoBaseTab {
    private static final String TAG = "DiskInfoSnapshotTab";
    private DiskSnapshotEntryAdapter adapter;
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private float lastTouchX = 0;

    public DiskInfoSnapshotTab(
        @NonNull DiskInfoActivity activity,
        @NonNull FrameLayout tabContainer
    ) {
        super(activity, tabContainer);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.partial_disk_snapshot_content;
    }

    @Override
    public boolean isShow() {
        var fmt = activity.config.getFormat();
        return DiskConfig.supportsSnapshot(fmt);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public void onCreateView() {
        recyclerView = view.findViewById(R.id.rv_snapshots);
        tvEmpty = view.findViewById(R.id.tv_empty);
        adapter = new DiskSnapshotEntryAdapter();
        adapter.setOnItemClickListener(this::onSnapshotClick);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
                    lastTouchX = e.getRawX();
                return false;
            }
        });
    }

    @Override
    public void onTabSelected() {
        activity.fab.setOnClickListener(v -> showCreateDialog());
        activity.fab.setVisibility(VISIBLE);
    }

    @Override
    public void onTabDeselected() {
        activity.fab.setVisibility(GONE);
    }

    @Override
    public void onConfigLoaded() {
    }

    @Override
    public void onDataLoaded() {
        var entries = new ArrayList<DiskSnapshotEntryAdapter.Entry>();
        var snapshots = activity.snapshots;
        if (snapshots != null && snapshots.length() > 0) {
            for (int i = 0; i < snapshots.length(); i++) {
                var snap = snapshots.optJSONObject(i);
                if (snap == null) continue;
                entries.add(buildEntry(snap));
            }
        }
        adapter.setItems(entries);
        if (entries.isEmpty()) {
            recyclerView.setVisibility(GONE);
            tvEmpty.setText(R.string.disk_info_no_snapshots);
            tvEmpty.setVisibility(VISIBLE);
        } else {
            recyclerView.setVisibility(VISIBLE);
            tvEmpty.setVisibility(GONE);
        }
    }

    @NonNull
    private DiskSnapshotEntryAdapter.Entry buildEntry(@NonNull JSONObject snap) {
        var name = snap.optString("name", "");
        var id = snap.optString("id", "");
        long vmStateSize = snap.optLong("vm-state-size", -1);
        long diskSize = snap.optLong("disk-size", -1);
        var dateStr = snap.optString("date-sec", "");
        var vmClock = snap.optString("vm-clock-sec", "");
        var sb = new StringBuilder();
        if (!id.isEmpty())
            sb.append(activity.getString(R.string.disk_info_snapshot_id, id));
        if (vmStateSize >= 0) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(activity.getString(R.string.disk_info_snapshot_vm_size,
                formatSize(vmStateSize)));
        }
        if (diskSize >= 0) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(activity.getString(R.string.disk_info_snapshot_disk_size,
                formatSize(diskSize)));
        }
        if (!dateStr.isEmpty()) {
            try {
                long seconds = Long.parseLong(dateStr);
                var df = getDateTimeInstance(MEDIUM, SHORT);
                var dateFormatted = df.format(new Date(seconds * 1000));
                if (sb.length() > 0) sb.append("\n");
                sb.append(activity.getString(R.string.disk_info_snapshot_date,
                    dateFormatted));
            } catch (NumberFormatException ignored) {
            }
        }
        if (!vmClock.isEmpty()) {
            try {
                long clockSec = Long.parseLong(vmClock);
                long h = clockSec / 3600;
                long m = (clockSec % 3600) / 60;
                long s = clockSec % 60;
                var clockStr = fmt("%02d:%02d:%02d", h, m, s);
                if (sb.length() > 0) sb.append("\n");
                sb.append(activity.getString(R.string.disk_info_snapshot_vm_clock, clockStr));
            } catch (NumberFormatException ignored) {
            }
        }
        var snapshotName = name.isEmpty() ? id : name;
        return new DiskSnapshotEntryAdapter.Entry(
            snapshotName, snapshotName, sb.toString()
        );
    }

    private void showCreateDialog() {
        var inf = LayoutInflater.from(activity);
        var dialogView = inf.inflate(R.layout.dialog_snapshot_create, null);
        TextInputLayout inputLayout = dialogView.findViewById(R.id.til_snapshot_name);
        TextInputEditText etName = dialogView.findViewById(R.id.et_snapshot_name);
        var dialog = new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.disk_info_snapshot_create_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        dialog.getButton(BUTTON_POSITIVE).setOnClickListener(v -> {
            var text = etName.getText();
            var name = text != null ? text.toString().trim() : "";
            if (name.isEmpty()) {
                inputLayout.setError(
                    activity.getString(R.string.disk_info_snapshot_create_error_empty));
                return;
            }
            inputLayout.setError(null);
            dialog.dismiss();
            warnIfUsedByCrosvm(() -> runSnapshotCommand("-c", name));
        });
    }

    /**
     * crosvm has no qcow2 snapshot support and refuses to open a snapshotted disk for writing,
     * so a snapshot silently makes any crosvm VM holding this disk writable un-startable (until
     * the pre-start prompt flattens it away again). Say so before creating one; QEMU-only and
     * read-only users are unaffected and see no dialog. The store scan is off the main thread.
     */
    private void warnIfUsedByCrosvm(@NonNull Runnable proceed) {
        var config = activity.config;
        if (config == null) return;
        var fullPath = config.getFullPath();
        runOnPool(() -> {
            String vmName = null;
            try {
                var store = new VMStore();
                store.load(activity);
                for (int i = 0; i < store.size() && vmName == null; i++) {
                    var vm = store.get(i);
                    if (optEnum(vm.item, "backend", VMBackend.DEFAULT) != VMBackend.CROSVM)
                        continue;
                    var disks = vm.item.opt("disks", null);
                    if (disks == null || !disks.is(DataItem.Type.ARRAY)) continue;
                    for (var disk : disks.asArray()) {
                        if (fullPath.equals(disk.optString("path", ""))
                            && !disk.optBoolean("readonly", false)) {
                            vmName = vm.getName();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to scan VMs for this disk", e);
            }
            final var attachedTo = vmName;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (attachedTo == null) {
                    proceed.run();
                    return;
                }
                new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.disk_snapshot_crosvm_warning_title)
                    .setMessage(activity.getString(
                        R.string.disk_snapshot_crosvm_warning_message, attachedTo))
                    .setPositiveButton(R.string.disk_snapshot_crosvm_warning_create,
                        (d, w) -> proceed.run())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        });
    }

    private void onSnapshotClick(
        @NonNull View view,
        @NonNull DiskSnapshotEntryAdapter.Entry entry
    ) {
        var menu = new MaterialMenu(activity, view);
        menu.inflate(R.menu.menu_snapshot_actions);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_snapshot_apply) {
                confirmApply(entry.snapshotName);
                return true;
            } else if (id == R.id.menu_snapshot_delete) {
                confirmDelete(entry.snapshotName);
                return true;
            }
            return false;
        });
        menu.showAtTouch(lastTouchX, 0);
    }

    private void confirmApply(@NonNull String name) {
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.disk_info_snapshot_apply)
            .setMessage(activity.getString(R.string.disk_info_snapshot_apply_message, name))
            .setPositiveButton(android.R.string.ok,
                (d, w) -> runSnapshotCommand("-a", name))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmDelete(@NonNull String name) {
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.disk_info_snapshot_delete)
            .setMessage(activity.getString(R.string.disk_info_snapshot_delete_message, name))
            .setPositiveButton(android.R.string.ok,
                (d, w) -> runSnapshotCommand("-d", name))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void runSnapshotCommand(@NonNull String flag, @NonNull String snapshotName) {
        var config = activity.config;
        if (config == null) return;
        var fullPath = config.getFullPath();
        runOnPool(() -> {
            // Snapshot commands rewrite the image; a disk other images overlay is locked.
            try {
                var diskStore = new DiskStore();
                diskStore.load(activity);
                int n = diskStore.childrenOf(config.getId()).size();
                if (n > 0) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing()) return;
                        new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.disk_locked_title)
                            .setMessage(activity.getString(
                                R.string.disk_locked_message, config.getName(), n))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    });
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to check disk children", e);
            }
            try {
                var result = runList(
                    getPrebuiltBinaryPath("qemu-img"),
                    "snapshot", flag, snapshotName, fullPath
                );
                result.printLog(TAG);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    if (result.isSuccess()) {
                        Toast.makeText(activity,
                            R.string.disk_info_snapshot_success, LENGTH_SHORT).show();
                        activity.reloadData();
                    } else {
                        showErrorDialog(result.getErrString());
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Snapshot command failed", e);
                var msg = e.getMessage() != null ? e.getMessage() : e.toString();
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    showErrorDialog(msg);
                });
            }
        });
    }

    private void showErrorDialog(@NonNull String message) {
        var dialog = new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.disk_info_snapshot_failed_title)
            .setView(R.layout.dialog_logs)
            .setPositiveButton(android.R.string.ok, null)
            .show();
        TextView tvLog = dialog.findViewById(R.id.tv_log);
        if (tvLog != null) tvLog.append(message.trim());
    }
}

