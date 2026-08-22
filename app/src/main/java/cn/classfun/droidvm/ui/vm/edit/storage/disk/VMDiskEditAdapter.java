package cn.classfun.droidvm.ui.vm.edit.storage.disk;

import static android.widget.Toast.LENGTH_SHORT;
import static android.widget.Toast.makeText;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.text.Editable;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.enums.Enums;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.ui.main.disk.DiskAdapter;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

public final class VMDiskEditAdapter extends CardItemAdapter<VMDiskEditViewHolder> {
    @FunctionalInterface
    public interface UefiVarsEnabledProvider {
        boolean isEnabled();
    }

    private OnItemClickListener browseFileListener;
    private OnItemClickListener importOrCreateListener;
    private UefiVarsEnabledProvider uefiVarsEnabledProvider = () -> true;
    private boolean readonlyChanged = false;
    private boolean updatingViews = false;

    public VMDiskEditAdapter(@NonNull Context context) {
        super(context);
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

    public void setPathAt(int position, String path) {
        if (position < 0 || position >= items.size()) return;
        var disk = items.get(position);
        if (path.toLowerCase().endsWith(".iso")) {
            disk.set("readonly", true);
            disk.set("bus", DiskBus.CDROM);
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
        holder.etPath.setText(disk.optString("path", ""));
        holder.switchReadonly.setChecked(disk.optBoolean("readonly", false));
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
            if (!readonlyChanged)
                item.set("readonly", newVal == DiskBus.CDROM);
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
            if (pos != RecyclerView.NO_POSITION) {
                removeItem(pos);
                // re-enable PFLASH for the remaining rows if it was the only one
                try {
                    notifyDataSetChanged();
                } catch (Exception ignored) {
                }
            }
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
