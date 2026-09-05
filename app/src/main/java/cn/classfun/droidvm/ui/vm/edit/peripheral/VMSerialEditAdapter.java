// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import static android.widget.Toast.LENGTH_SHORT;
import static android.widget.Toast.makeText;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.SerialBackend;
import cn.classfun.droidvm.lib.store.vm.SerialHardware;
import cn.classfun.droidvm.lib.store.vm.VMSerialConfig;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.ui.main.settings.MainSettingsFragment;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

/**
 * The serial-port list's rows. The fixed 16550 quartet is always present (crosvm creates those
 * ports whether asked to or not, so hiding them would misrepresent the guest) and can only
 * change backend; added rows -- SBSA, virtio-console -- carry a delete button. The + button asks
 * for the hardware kind first, because the kind decides how many can exist and what the row
 * shows.
 */
public final class VMSerialEditAdapter extends CardItemAdapter<VMSerialEditViewHolder> {
    // True while onBindViewHolder is programmatically setting view values. The backend picker
    // fires its value-changed listener from setSelectedItem() inside configure(), and on a
    // recycled row that hits the PREVIOUS bind's listener -- which calls notifyItemChanged and
    // starts an every-frame rebind loop (flickering row, untypeable path field, and finally
    // "parameter must be a descendant of this view" when layout chases the replaced focused
    // view). Same idiom as VMDiskEditAdapter.
    private boolean updatingViews = false;

    public VMSerialEditAdapter(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onAddRequested(@NonNull View anchor) {
        var kinds = new ArrayList<SerialHardware>();
        for (var hw : SerialHardware.values()) {
            if (!hw.isAddable()) continue;
            if (countOf(hw) >= hw.getMaxPorts()) continue;
            kinds.add(hw);
        }
        if (kinds.isEmpty()) {
            makeText(context, R.string.edit_vm_serial_full, LENGTH_SHORT).show();
            return;
        }
        var menu = new PopupMenu(context, null).getMenu();
        for (int i = 0; i < kinds.size(); i++) {
            var hw = kinds.get(i);
            var item = menu.add(Menu.NONE, hw.ordinal(), i, hw.getDisplayString(context));
            item.setIcon(hw.getIconId());
        }
        new MenuDialogBuilder(context)
            .setTitle(R.string.edit_vm_serial_add_title)
            .setMenu(menu)
            .setListener(item -> {
                addPort(SerialHardware.values()[item.getItemId()]);
                return true;
            })
            .show();
    }

    private int countOf(@NonNull SerialHardware hw) {
        int n = 0;
        for (var iter : items)
            if (new VMSerialConfig(iter.getValue()).getHardware() == hw) n++;
        return n;
    }

    /** Smallest unused 1-based port number for {@code hw}, so removals leave no permanent gap. */
    private int nextNum(@NonNull SerialHardware hw) {
        var used = new boolean[hw.getMaxPorts() + 1];
        for (var iter : items) {
            var port = new VMSerialConfig(iter.getValue());
            if (port.getHardware() != hw) continue;
            var num = port.getNum();
            if (num >= 1 && num < used.length) used[num] = true;
        }
        for (int num = 1; num < used.length; num++)
            if (!used[num]) return num;
        return hw.getMaxPorts();
    }

    private void addPort(@NonNull SerialHardware hw) {
        var entry = DataItem.newObject();
        var port = new VMSerialConfig(entry);
        port.setHardware(hw);
        port.setNum(nextNum(hw));
        // A port someone bothered to add is a port they want to talk to.
        port.setBackend(SerialBackend.APP_CONSOLE);
        appendItem(entry);
    }

    @NonNull
    @Override
    protected VMSerialEditViewHolder createViewHolderInstance(@NonNull View view) {
        return new VMSerialEditViewHolder(view);
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.item_vm_serial_edit;
    }

    @Override
    public void onBindViewHolder(@NonNull VMSerialEditViewHolder holder, int position) {
        holder.unbindWatchers();
        var port = new VMSerialConfig(items.get(position));
        var backend = port.getBackend();

        updatingViews = true;
        try {
            // The suffix follows the radio so its meaning is self-explaining: the checked
            // port is the one SPCR points at.
            holder.tvTitle.setText(port.isConsole()
                ? fmt("%s (SPCR)", port.getDisplayName(context))
                : port.getDisplayName(context));
            holder.btnDelete.setVisibility(port.isFixed() ? View.GONE : View.VISIBLE);
            holder.btnBackend.configure(SerialBackend.class, backend);

            var showPath = backend.usesPath();
            holder.tilPath.setVisibility(showPath ? View.VISIBLE : View.GONE);
            if (showPath) {
                holder.tilPath.setHint(context.getString(backend == SerialBackend.PTY
                    ? R.string.edit_vm_serial_path_pty_hint : R.string.edit_vm_serial_path));
                holder.etPath.setText(port.getPath());
            }

            // No baud/settings group: every backend of a virtual wire ignores line speed, so
            // the row does not pretend otherwise. USB ACM does carry one choice -- which pool
            // slot to attach, so the host COM a VM lands on never depends on boot order.
            var usb = backend == SerialBackend.USB_ACM;
            holder.btnUsbSlot.setVisibility(usb ? View.VISIBLE : View.GONE);
            if (usb)
                holder.btnUsbSlot.setText(fmt("%s: %s",
                    context.getString(R.string.edit_vm_serial_usb_slot),
                    context.getString(R.string.edit_vm_serial_usb_slot_item,
                        port.getUsbSlot())));

            // Single-select across every row: this is the port SPCR/stdout-path names, and
            // there is exactly one of those per machine. Any backend qualifies -- a sink
            // console is a deliberate "discard the guest console" and is the user's call.
            holder.radioConsole.setChecked(port.isConsole());
        } finally {
            updatingViews = false;
        }

        holder.radioConsole.setOnClickListener(v -> {
            if (updatingViews) return;
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            for (int i = 0; i < items.size(); i++)
                new VMSerialConfig(items.get(i)).setConsole(i == pos);
            notifyDataSetChangedSafe();
        });

        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                removeItem(pos);
        });
        holder.btnBackend.setOnValueChangedListener((oldVal, newVal) -> {
            if (updatingViews) return;
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            new VMSerialConfig(items.get(pos)).setBackend((SerialBackend) newVal);
            // Configuring is allowed either way, but a VM with an ACM port refuses to boot
            // until the feature is switched on -- say so now, not at boot time.
            if (newVal == SerialBackend.USB_ACM
                && !MainSettingsFragment.isUsbAcmEnabled(context))
                makeText(context, R.string.edit_vm_serial_usb_disabled_hint, LENGTH_SHORT)
                    .show();
            notifyItemChangedSafe(pos);
        });
        if (backend.usesPath()) {
            holder.pathWatcher = new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (updatingViews) return;
                    int pos = holder.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION)
                        new VMSerialConfig(items.get(pos)).setPath(s.toString());
                }
            };
            holder.etPath.addTextChangedListener(holder.pathWatcher);
        }
        if (backend == SerialBackend.USB_ACM) {
            holder.btnUsbSlot.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION)
                    showUsbSlotPicker(pos);
            });
        }
    }

    /** Slots come from the app-wide pool-size setting; each is one stable host COM port. */
    private void showUsbSlotPicker(int position) {
        var slots = MainSettingsFragment.getUsbAcmPorts(context);
        var menu = new PopupMenu(context, null).getMenu();
        for (int i = 0; i < slots; i++)
            menu.add(Menu.NONE, i, i,
                context.getString(R.string.edit_vm_serial_usb_slot_item, i));
        new MenuDialogBuilder(context)
            .setTitle(R.string.edit_vm_serial_usb_slot)
            .setMenu(menu)
            .setListener(item -> {
                new VMSerialConfig(items.get(position)).setUsbSlot(item.getItemId());
                notifyItemChangedSafe(position);
                return true;
            })
            .show();
    }

    private void notifyItemChangedSafe(int position) {
        try {
            notifyItemChanged(position);
        } catch (Exception ignored) {
            mainHandler.post(() -> notifyItemChanged(position));
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void notifyDataSetChangedSafe() {
        try {
            notifyDataSetChanged();
        } catch (Exception ignored) {
            mainHandler.post(this::notifyDataSetChanged);
        }
    }
}
