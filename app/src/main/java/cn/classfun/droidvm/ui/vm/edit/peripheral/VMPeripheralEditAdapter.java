// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.HostAudioDevices;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

/**
 * The peripheral list's rows. Unlike the disk/NIC lists a row is not blank to begin with -- the
 * + button first asks which kind of peripheral this is, because the kind decides what the rest
 * of the row means -- so this overrides {@link #onAddRequested}.
 *
 * <p>The host-device picker is built from what the phone reports right now
 * ({@link HostAudioDevices}); the row stores the stable descriptor, not the live id, and warns
 * when the stored device is not currently connected.</p>
 */
public final class VMPeripheralEditAdapter extends CardItemAdapter<VMPeripheralEditViewHolder> {
    /** Asks for RECORD_AUDIO before a microphone row is added; set by the tab. */
    private Consumer<Runnable> micPermissionGate;
    // What the phone reported last time we looked, so binding a row is not a binder call each
    // time it scrolls past. Dropped by refreshHostDevices() -- which the tab calls whenever it
    // becomes visible, since something may have been plugged in meanwhile.
    private List<HostAudioDevices.Entry> outputCache;
    private List<HostAudioDevices.Entry> inputCache;

    public VMPeripheralEditAdapter(@NonNull Context context) {
        super(context);
    }

    /**
     * Runs the given action only after the host mic permission has been asked for. Without a
     * gate set, microphone rows are added straight away.
     */
    public void setMicPermissionGate(@Nullable Consumer<Runnable> gate) {
        this.micPermissionGate = gate;
    }

    @NonNull
    @Override
    protected VMPeripheralEditViewHolder createViewHolderInstance(@NonNull View view) {
        return new VMPeripheralEditViewHolder(view);
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.item_vm_peripheral_edit;
    }

    @Override
    public void onAddRequested(@NonNull View anchor) {
        // Built from the enum rather than a menu resource, so a new peripheral kind shows up
        // here the moment it is added to PeripheralType.
        var menu = new PopupMenu(context, null).getMenu();
        for (var type : PeripheralType.values()) {
            var item = menu.add(Menu.NONE, type.ordinal(), type.ordinal(),
                type.getDisplayString(context));
            item.setIcon(type.getIconId());
        }
        new MenuDialogBuilder(context)
            .setTitle(R.string.edit_vm_peripheral_add_title)
            .setMenu(menu)
            .setListener(item -> {
                addPeripheral(PeripheralType.values()[item.getItemId()]);
                return true;
            })
            .show();
    }

    private void addPeripheral(@NonNull PeripheralType type) {
        Runnable add = () -> {
            var item = DataItem.newObject();
            new VMPeripheralConfig(item).setType(type);
            appendItem(item);
        };
        if (type.needsRecordPermission() && micPermissionGate != null) {
            micPermissionGate.accept(add);
            return;
        }
        add.run();
    }

    @Override
    public void onBindViewHolder(@NonNull VMPeripheralEditViewHolder holder, int position) {
        var peripheral = new VMPeripheralConfig(items.get(position));
        var type = peripheral.getType();
        holder.ivIcon.setImageResource(type.getIconId());
        holder.tvType.setText(type.getDisplayString(context));
        bindHostDevice(holder, peripheral);
        holder.btnHost.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                showHostPicker(holder, pos);
        });
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                removeItem(pos);
        });
    }

    /**
     * Names the chosen endpoint on the button, and says so when the config points at something
     * that is not plugged in or paired right now -- the VM will still start, just on the host's
     * default routing.
     */
    private void bindHostDevice(
        @NonNull VMPeripheralEditViewHolder holder, @NonNull VMPeripheralConfig peripheral
    ) {
        var key = peripheral.getHostDevice();
        if (key.isEmpty()) {
            holder.btnHost.setText(R.string.edit_vm_peripheral_host_default);
            holder.tvWarning.setVisibility(GONE);
            return;
        }
        var live = findLive(peripheral.getType().isInput(), key);
        var label = live != null ? live.label : peripheral.getHostLabel();
        holder.btnHost.setText(label.isEmpty() ? key : label);
        holder.tvWarning.setText(R.string.edit_vm_peripheral_host_missing);
        holder.tvWarning.setVisibility(live != null ? GONE : VISIBLE);
    }

    @Nullable
    private HostAudioDevices.Entry findLive(boolean input, @NonNull String key) {
        for (var entry : liveDevices(input))
            if (entry.key.equals(key)) return entry;
        return null;
    }

    @NonNull
    private List<HostAudioDevices.Entry> liveDevices(boolean input) {
        if (input) {
            if (inputCache == null) inputCache = HostAudioDevices.list(context, true);
            return inputCache;
        }
        if (outputCache == null) outputCache = HostAudioDevices.list(context, false);
        return outputCache;
    }

    private void showHostPicker(@NonNull VMPeripheralEditViewHolder holder, int position) {
        var peripheral = new VMPeripheralConfig(items.get(position));
        boolean input = peripheral.getType().isInput();
        var keys = new ArrayList<String>();
        var labels = new ArrayList<String>();
        // "" is the platform's own routing: the entry to pick when the VM should just follow
        // whatever the phone is doing (headset plugged in mid-call, and so on).
        keys.add("");
        labels.add(context.getString(R.string.edit_vm_peripheral_host_default));
        for (var entry : liveDevices(input)) {
            keys.add(entry.key);
            labels.add(entry.label);
        }
        // A configured-but-absent device stays selectable so opening the picker cannot silently
        // drop it.
        var current = peripheral.getHostDevice();
        if (!current.isEmpty() && !keys.contains(current)) {
            keys.add(current);
            var stale = peripheral.getHostLabel();
            labels.add(context.getString(R.string.edit_vm_peripheral_host_missing_item,
                stale.isEmpty() ? current : stale));
        }
        int checked = Math.max(0, keys.indexOf(current));
        DialogInterface.OnClickListener onClick = (dialog, which) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                var picked = new VMPeripheralConfig(items.get(pos));
                picked.setHostDevice(keys.get(which),
                    which == 0 ? "" : labels.get(which));
                bindHostDevice(holder, picked);
            }
            dialog.dismiss();
        };
        new MaterialAlertDialogBuilder(context)
            .setTitle(input
                ? R.string.edit_vm_peripheral_pick_input
                : R.string.edit_vm_peripheral_pick_output)
            .setSingleChoiceItems(labels.toArray(new String[0]), checked, onClick)
            .show();
    }

    /** Re-reads the live device list, e.g. after the user plugged something in. */
    @SuppressLint("NotifyDataSetChanged")
    public void refreshHostDevices() {
        outputCache = null;
        inputCache = null;
        notifyDataSetChanged();
    }
}
