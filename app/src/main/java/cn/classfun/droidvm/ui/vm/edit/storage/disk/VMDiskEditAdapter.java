// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.storage.disk;

import static android.widget.Toast.LENGTH_SHORT;
import static android.widget.Toast.makeText;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.enums.Enums;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursors;
import cn.classfun.droidvm.ui.disk.tree.DiskBranchPanel;
import cn.classfun.droidvm.ui.main.disk.DiskAdapter;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

/**
 * The VM's disk rows. A row's read-only switch has two layers: what the user chose, and what
 * the disk's situation forces - a base that has overlays must not be written, and neither may a
 * disk two rows here both attach. Forced state is derived on every bind from the registry and
 * the rows themselves; it only ever adds read-only, and the user's own choice comes back once
 * the force is gone (overlays deleted, the duplicate row re-pointed).
 *
 * <p>A disk ANOTHER VM attaches is deliberately not forced here (see
 * {@link cn.classfun.droidvm.ui.vm.VmDiskSharing}): sharing only corrupts anything while both
 * VMs hold the file, which is a question about run state at start time, not about how the
 * config was saved. {@code VMActions}' pre-start guard asks it then, and flips only that
 * start's attachments.
 */
public final class VMDiskEditAdapter extends CardItemAdapter<VMDiskEditViewHolder> {
    @FunctionalInterface
    public interface UefiVarsEnabledProvider {
        boolean isEnabled();
    }

    @FunctionalInterface
    public interface OnItemMovedListener {
        void onItemMoved(int from, int to);
    }

    private OnItemClickListener importOrCreateListener;
    @Nullable
    private OnItemMovedListener itemMovedListener;
    private UefiVarsEnabledProvider uefiVarsEnabledProvider = () -> true;
    private boolean readonlyChanged = false;
    private boolean updatingViews = false;
    // Full paths of registered disks that have overlays. Loaded async; rows re-check on bind.
    private final Set<String> baseOfOverlays = new HashSet<>();
    // The user's own read-only choice per row, kept apart from the forced value written into
    // the item. Identity-keyed: the row DataItems are stable objects for the adapter's life.
    private final Map<DataItem, Boolean> userReadonly = new IdentityHashMap<>();
    @Nullable
    private UUID editingVmId;
    @NonNull
    private String editingVmName = "";

    public VMDiskEditAdapter(@NonNull Context context) {
        super(context);
        reloadLocks(null);
    }

    /** Which VM this editor belongs to (null id for one never saved), for the branch panel. */
    public void setEditingVm(@Nullable UUID id, @Nullable String name) {
        editingVmId = id;
        editingVmName = name == null ? "" : name;
        reloadLocks(null);
    }

    public void reloadLockedPaths() {
        reloadLocks(null);
    }

    /** @param onDone runs on the main thread once the cache reflects the disk registry. */
    @SuppressLint("NotifyDataSetChanged")
    public void reloadLocks(@Nullable Runnable onDone) {
        runOnPool(() -> {
            var bases = new HashSet<String>();
            try {
                var store = new DiskStore();
                store.load(context);
                for (int i = 0; i < store.size(); i++) {
                    var cfg = store.get(i);
                    if (store.hasChildren(cfg.getId()))
                        bases.add(cfg.getFullPath());
                }
            } catch (Exception ignored) {
            }
            mainHandler.post(() -> {
                baseOfOverlays.clear();
                baseOfOverlays.addAll(bases);
                try {
                    notifyDataSetChanged();
                } catch (Exception ignored) {
                }
                if (onDone != null) onDone.run();
            });
        });
    }

    /** The string explaining why {@code path} is forced read-only, or 0 when it isn't. */
    @StringRes
    private int forcedReason(@NonNull String path, int position) {
        if (path.isEmpty()) return 0;
        if (baseOfOverlays.contains(path)) return R.string.edit_vm_disk_locked_readonly;
        for (int i = 0; i < items.size(); i++) {
            if (i == position) continue;
            var d = items.get(i);
            if (d.is(DataItem.Type.OBJECT) && path.equals(d.optString("path", "")))
                return R.string.edit_vm_disk_shared_readonly;
        }
        return 0;
    }

    private boolean isForced(@NonNull String path, int position) {
        return forcedReason(path, position) != 0;
    }

    private boolean userReadonly(@NonNull DataItem disk) {
        var v = userReadonly.get(disk);
        if (v == null) {
            v = disk.optBoolean("readonly", false);
            userReadonly.put(disk, v);
        }
        return v;
    }

    /** Write the effective read-only (forced or chosen) into the row; returns whether forced. */
    private boolean applyReadonly(@NonNull DataItem disk, int position) {
        boolean forced = isForced(disk.optString("path", ""), position);
        disk.set("readonly", forced || userReadonly(disk));
        return forced;
    }

    /** Recompute every row's effective read-only; call before the rows are saved. */
    public void commitReadonly() {
        for (int i = 0; i < items.size(); i++) {
            var d = items.get(i);
            if (d.is(DataItem.Type.OBJECT)) applyReadonly(d, i);
        }
    }

    @SuppressWarnings("unused")
    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    public void setOnImportOrCreateListener(OnItemClickListener l) {
        this.importOrCreateListener = l;
    }

    /** Drag reorder happened; the boot tab's disk index has to follow the same disk. */
    public void setOnItemMovedListener(@Nullable OnItemMovedListener l) {
        this.itemMovedListener = l;
    }

    @Override
    protected void onItemMoved(int from, int to) {
        if (itemMovedListener != null) itemMovedListener.onItemMoved(from, to);
    }

    public void setUefiVarsEnabledProvider(@NonNull UefiVarsEnabledProvider provider) {
        this.uefiVarsEnabledProvider = provider;
    }

    private boolean pflashAllowed(int position) {
        if (uefiVarsEnabledProvider.isEnabled()) return false;
        for (int i = 0; i < items.size(); i++) {
            if (i == position) continue;
            var d = items.get(i);
            if (d.is(DataItem.Type.OBJECT)
                && Enums.optEnum(d, "bus", DiskBus.VIRTIO) == DiskBus.PFLASH)
                return false;
        }
        return true;
    }

    @NonNull
    private DiskBus[] busOptions(int position) {
        if (!pflashAllowed(position))
            return new DiskBus[]{DiskBus.VIRTIO, DiskBus.SCSI, DiskBus.PMEM, DiskBus.CDROM};
        return new DiskBus[]{DiskBus.VIRTIO, DiskBus.SCSI, DiskBus.PMEM, DiskBus.CDROM, DiskBus.PFLASH};
    }

    private static boolean containsPflash(@NonNull DiskBus[] options) {
        for (var bus : options)
            if (bus == DiskBus.PFLASH) return true;
        return false;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setPathAt(int position, String path) {
        if (position < 0 || position >= items.size()) return;
        var disk = items.get(position);
        if (path.toLowerCase().endsWith(".iso")) {
            userReadonly.put(disk, true);
            disk.set("bus", DiskBus.CDROM);
        }
        boolean wasForced = isForced(disk.optString("path", ""), position);
        disk.set("path", path);
        boolean forced = applyReadonly(disk, position);
        if (forced && !wasForced)
            makeText(context, forcedReason(path, position), LENGTH_SHORT).show();
        try {
            // Another row sharing this path changes its forced state too.
            notifyDataSetChanged();
        } catch (Exception ignored) {
        }
    }

    // Handles path edits coming from the user typing in the field. Updates the
    // data model (and the dependent views) directly without notifyItemChanged,
    // which would rebind the row and reset the EditText cursor to the start.
    private void onPathTyped(VMDiskEditViewHolder holder, int position, String path) {
        if (position < 0 || position >= items.size()) return;
        var disk = items.get(position);
        disk.set("path", path);
        boolean iso = path.toLowerCase().endsWith(".iso");
        if (iso) {
            userReadonly.put(disk, true);
            disk.set("bus", DiskBus.CDROM);
        }
        boolean forced = applyReadonly(disk, position);
        updatingViews = true;
        try {
            holder.switchReadonly.setChecked(disk.optBoolean("readonly", false));
            holder.switchReadonly.setEnabled(!forced);
            if (iso) holder.btnBus.setSelectedItem(DiskBus.CDROM);
        } finally {
            updatingViews = false;
        }
    }

    @NonNull
    @Override
    protected VMDiskEditViewHolder createViewHolderInstance(@NonNull View view) {
        return new VMDiskEditViewHolder(view);
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.item_vm_disk_edit;
    }

    @Override
    public void onBindViewHolder(@NonNull VMDiskEditViewHolder holder, int position) {
        var disk = items.get(position);
        holder.unbindWatcher();
        var path = disk.optString("path", "");
        holder.etPath.setText(path);
        boolean forced = applyReadonly(disk, position);
        // A recycled holder still carries the previous bind's listener: detach it first, or the
        // programmatic setChecked below would be recorded as the user's own choice.
        holder.switchReadonly.setOnCheckedChangeListener(null);
        holder.switchReadonly.setChecked(disk.optBoolean("readonly", false));
        holder.switchReadonly.setEnabled(!forced);
        // unselectable PFLASH and fallback to VIRTIO.
        var bus = Enums.optEnum(disk, "bus", DiskBus.VIRTIO);
        var options = busOptions(position);
        if (bus == DiskBus.PFLASH && !containsPflash(options)) {
            bus = DiskBus.VIRTIO;
            if (!updatingViews) disk.set("bus", bus);
        }
        holder.btnBus.setItems(options);
        holder.btnBus.setSelectedItem(bus);
        holder.btnBus.setOnValueChangedListener((oldVal, newVal) -> {
            if (updatingViews) return;
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            var item = items.get(pos);
            item.set("bus", newVal);
            if (!readonlyChanged) userReadonly.put(item, newVal == DiskBus.CDROM);
            applyReadonly(item, pos);
            try {
                // PFLASH exclusivity changes other rows' options, so a
                // PFLASH switch needs a full refresh; any other
                // bus change only affects this row.
                if (oldVal == DiskBus.PFLASH || newVal == DiskBus.PFLASH) {
                    notifyDataSetChanged();
                } else {
                    notifyItemChanged(pos);
                }
            } catch (Exception ignored) {
            }
        });
        holder.pathWatcher = new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION)
                    onPathTyped(holder, pos, s.toString());
            }
        };
        holder.etPath.addTextChangedListener(holder.pathWatcher);
        holder.switchReadonly.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingViews) return;
            int pos = holder.getBindingAdapterPosition();
            readonlyChanged = true;
            if (pos != RecyclerView.NO_POSITION) {
                var item = items.get(pos);
                userReadonly.put(item, checked);
                item.set("readonly", checked);
            }
        });
        holder.btnBrowse.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            showBrowseDialog(pos);
        });
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                removeItem(pos);
                // re-enable PFLASH for the remaining rows if it was the only one, and re-derive
                // sharing for a row that pointed at the same disk
                try {
                    notifyDataSetChanged();
                } catch (Exception ignored) {
                }
            }
        });
        holder.btnBranches.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                showBranches(pos);
        });
    }

    private void showBrowseDialog(int position) {
        MenuItem.OnMenuItemClickListener listener = item -> {
            var id = item.getItemId();
            if (id == R.id.menu_disk_browse_registered) {
                showRegisteredDiskDialog(position);
            } else if (id == R.id.menu_disk_browse_import_create) {
                if (importOrCreateListener != null)
                    importOrCreateListener.onItemClick(position);
            }
            return true;
        };
        MenuDialogBuilder.showSimple(
            context,
            R.string.edit_vm_disk_browse_title,
            R.menu.menu_vm_disk_browse,
            listener
        );
    }

    /**
     * Branch management for this row's disk: the whole overlay family, where another node can be
     * picked, and branches can be created/deleted/merged/flattened in place while the panel
     * stays open. The rows here travel with the panel as in-memory cursors and come back when it
     * closes: OK applies the pick to this row, Back applies where each row's cursor ended up,
     * and a row whose whole tree is gone is removed. The lock caches reload BEFORE the rows are
     * re-pointed, so a base that just lost its last overlay is not re-forced read-only.
     */
    private void showBranches(int position) {
        if (position < 0 || position >= items.size()) return;
        var path = items.get(position).optString("path", "");
        if (path.isEmpty()) {
            makeText(context, R.string.disk_tree_not_registered, LENGTH_SHORT).show();
            return;
        }
        var paths = new ArrayList<String>();
        var readonly = new ArrayList<Boolean>();
        for (int i = 0; i < items.size(); i++) {
            var d = items.get(i);
            paths.add(d.optString("path", ""));
            readonly.add(d.optBoolean("readonly", false));
        }
        var live = new AttachmentCursors.LiveRows(
            editingVmId, editingVmName, paths, readonly, position);
        DiskBranchPanel.open(context, path, live, result ->
            reloadLocks(() -> applyPanelResult(result)));
    }

    @SuppressLint("NotifyDataSetChanged")
    private void applyPanelResult(@NonNull DiskBranchPanel.Result result) {
        var removals = new ArrayList<Integer>();
        for (var e : result.rows.entrySet()) {
            if (e.getValue() == null) removals.add(e.getKey());
            else if (e.getKey() < items.size()) setPathAt(e.getKey(), e.getValue());
        }
        removals.sort((a, b) -> Integer.compare(b, a));
        for (int idx : removals)
            if (idx < items.size()) removeItem(idx);
        try {
            notifyDataSetChanged();
        } catch (Exception ignored) {
        }
    }

    private void showRegisteredDiskDialog(int position) {
        var store = new DiskStore();
        Runnable done = () -> {
            if (store.isEmpty()) {
                makeText(context,
                    R.string.edit_vm_disk_no_registered,
                    LENGTH_SHORT
                ).show();
                return;
            }
            var recyclerView = new RecyclerView(context);
            recyclerView.setLayoutManager(new LinearLayoutManager(context));
            var adapter = new DiskAdapter();
            adapter.items.replace(store);
            adapter.onItemsUpdated();
            recyclerView.setAdapter(adapter);
            var dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.edit_vm_disk_browse_registered)
                .setView(recyclerView)
                .show();
            adapter.setOnItemClickListener((v, disk) -> {
                setPathAt(position, disk.getFullPath());
                dialog.dismiss();
            });
        };
        runOnPool(() -> {
            store.load(context);
            mainHandler.post(done);
        });
    }
}
