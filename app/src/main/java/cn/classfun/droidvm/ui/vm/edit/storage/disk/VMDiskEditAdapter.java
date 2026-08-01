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

import java.util.HashSet;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.enums.Enums;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.ui.disk.tree.DiskTreeDialog;
import cn.classfun.droidvm.ui.main.disk.DiskAdapter;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

public final class VMDiskEditAdapter extends CardItemAdapter<VMDiskEditViewHolder> {
    private OnItemClickListener browseFileListener;
    private OnItemClickListener importOrCreateListener;
    private boolean readonlyChanged = false;
    private boolean updatingViews = false;
    // Full paths of registered disks that have overlays: those must attach read-only (writing
    // to a base corrupts its overlays). Loaded async at construction; rows re-check on bind.
    private final Set<String> lockedPaths = new HashSet<>();

    public VMDiskEditAdapter(@NonNull Context context) {
        super(context);
        reloadLockedPaths();
    }

    public void reloadLockedPaths() {
        reloadLockedPaths(null);
    }

    /** @param onDone runs on the main thread once the cache reflects the current registry. */
    @SuppressLint("NotifyDataSetChanged")
    public void reloadLockedPaths(@Nullable Runnable onDone) {
        runOnPool(() -> {
            var found = new HashSet<String>();
            try {
                var store = new DiskStore();
                store.load(context);
                for (int i = 0; i < store.size(); i++) {
                    var cfg = store.get(i);
                    if (store.hasChildren(cfg.getId()))
                        found.add(cfg.getFullPath());
                }
            } catch (Exception ignored) {
            }
            mainHandler.post(() -> {
                lockedPaths.clear();
                lockedPaths.addAll(found);
                try {
                    notifyDataSetChanged();
                } catch (Exception ignored) {
                }
                if (onDone != null) onDone.run();
            });
        });
    }

    private boolean isLockedPath(@NonNull String path) {
        return lockedPaths.contains(path);
    }

    @SuppressWarnings("unused")
    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    public void setOnBrowseFileListener(OnItemClickListener l) {
        this.browseFileListener = l;
    }

    public void setOnImportOrCreateListener(OnItemClickListener l) {
        this.importOrCreateListener = l;
    }

    public void setPathAt(int position, String path) {
        if (position < 0 || position >= items.size()) return;
        var disk = items.get(position);
        if (path.toLowerCase().endsWith(".iso")) {
            disk.set("readonly", true);
            disk.set("bus", DiskBus.CDROM);
        }
        // Read-only is forced while a disk has overlays. When the new target has none, undo the
        // force we applied to the old one - otherwise a disk stays stuck read-only after its
        // overlays are gone. A read-only the user set themselves on an unlocked disk is kept.
        boolean wasLocked = isLockedPath(disk.optString("path", ""));
        if (isLockedPath(path)) {
            disk.set("readonly", true);
            makeText(context, R.string.edit_vm_disk_locked_readonly, LENGTH_SHORT).show();
        } else if (wasLocked) {
            disk.set("readonly", false);
        }
        disk.set("path", path);
        try {
            notifyItemChanged(position);
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
        if (path.toLowerCase().endsWith(".iso")) {
            disk.set("readonly", true);
            disk.set("bus", DiskBus.CDROM);
            updatingViews = true;
            try {
                holder.switchReadonly.setChecked(true);
                holder.btnBus.setSelectedItem(DiskBus.CDROM);
            } finally {
                updatingViews = false;
            }
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
        boolean locked = isLockedPath(path);
        if (locked && !disk.optBoolean("readonly", false))
            disk.set("readonly", true);
        holder.switchReadonly.setChecked(disk.optBoolean("readonly", false));
        holder.switchReadonly.setEnabled(!locked);
        holder.btnBus.configure(DiskBus.class, Enums.optEnum(disk, "bus", DiskBus.VIRTIO));
        holder.btnBus.setOnValueChangedListener((oldVal, newVal) -> {
            if (updatingViews) return;
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            var item = items.get(pos);
            item.set("bus", newVal);
            if (!readonlyChanged)
                item.set("readonly", newVal == DiskBus.CDROM);
            try {
                notifyItemChanged(pos);
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
            if (pos != RecyclerView.NO_POSITION)
                items.get(pos).set("readonly", checked);
        });
        holder.btnBrowse.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            showBrowseDialog(pos);
        });
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                removeItem(pos);
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
            if (id == R.id.menu_disk_browse_file) {
                if (browseFileListener != null)
                    browseFileListener.onItemClick(position);
            } else if (id == R.id.menu_disk_browse_registered) {
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
     * Branch management: the current disk's whole overlay family, where another node can be
     * picked to re-point this attachment (locked nodes force read-only via {@link #setPathAt})
     * and branches can be created/deleted/merged/flattened in place. After any of that the
     * registry may have changed, so the locked-path cache reloads.
     */
    private void showBranches(int position) {
        if (position < 0 || position >= items.size()) return;
        var path = items.get(position).optString("path", "");
        if (path.isEmpty()) {
            makeText(context, R.string.disk_tree_not_registered, LENGTH_SHORT).show();
            return;
        }
        // Refresh the lock cache BEFORE applying a pick: the tree may have just deleted or
        // merged the branch that locked it, and a stale cache would re-force read-only.
        DiskTreeDialog.show(context, path,
            picked -> reloadLockedPaths(() -> setPathAt(position, picked.getFullPath())),
            replacement -> reloadLockedPaths(() -> {
                if (replacement.isEmpty()) {
                    // Nothing left to attach: clear the row rather than point at a deleted file.
                    items.get(position).set("path", "");
                    notifyItemChanged(position);
                } else {
                    setPathAt(position, replacement);
                }
            }));
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
