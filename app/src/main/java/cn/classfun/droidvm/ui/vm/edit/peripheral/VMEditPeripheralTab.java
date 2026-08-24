// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.view.View;

import androidx.annotation.NonNull;
import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.data.HostAudioDevices;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.SerialBackend;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VMSerialConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.widgets.container.CardItemListView;

/**
 * Peripherals attached to the VM. Today that means audio endpoints, which the crosvm backend
 * turns into one virtio-snd card: speakers become its output PCM devices, microphones its input
 * ones, each pinned to the host endpoint picked here.
 */
public final class VMEditPeripheralTab extends VMEditBaseTab {
    private CardItemListView listPeripherals;
    private CardItemListView listSerialPorts;
    private VMPeripheralEditAdapter adapter;

    public VMEditPeripheralTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        listPeripherals = view.findViewById(R.id.list_peripherals);
        listSerialPorts = view.findViewById(R.id.list_serial_ports);
    }

    @Override
    public void initValue() {
        adapter = listPeripherals.setAdapter(VMPeripheralEditAdapter.class);
        adapter.setMicPermissionGate(parent.getRecordAudioPermission()::ensureThen);
        listSerialPorts.setAdapter(VMSerialEditAdapter.class);
        // A brand-new VM never goes through loadConfig, but its serial list is not empty:
        // the fixed COM quartet exists either way, so show it (COM1 as the app console).
        var scratch = DataItem.newObject();
        VMSerialConfig.ensureDefaults(scratch);
        listSerialPorts.setItems(scratch.opt(VMSerialConfig.KEY, DataItem.newArray()));
    }

    @Override
    public void onTabShown() {
        // The host device list is live: something may have been plugged in or paired since the
        // rows were last bound.
        if (adapter != null) adapter.refreshHostDevices();
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        listPeripherals.setItems(config.item.opt("peripherals", DataItem.newArray()));
        // VMConfig's constructor already materialized the fixed quartet for configs from
        // before "serial_ports"; ensureDefaults here only covers configs built by hand.
        VMSerialConfig.ensureDefaults(config.item);
        listSerialPorts.setItems(config.item.opt(VMSerialConfig.KEY, DataItem.newArray()));
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        // Two endpoints pointed at one host device in the same direction would open two AAudio
        // streams onto it: allowed by the platform, but never what someone meant to configure.
        // The direction is part of the identity, so a microphone and a speaker cannot collide.
        // Checked across every card, not within one: two cards aimed at the same speaker is the
        // same mistake as one card aimed at it twice.
        var seen = new ArrayList<String>();
        for (var peripheral : VMPeripheralConfig.listOf(wrap())) {
            if (peripheral.getType() != PeripheralType.VIRTIO_SOUND) continue;
            for (var endpoint : peripheral.getEndpoints()) {
                var key = endpoint.getHostDevice();
                // Unset, or the platform's own routing, can be chosen as often as one likes:
                // it does not name a device, so there is nothing to collide over.
                if (key.isEmpty() || HostAudioDevices.SYSTEM_DEFAULT_KEY.equals(key)) continue;
                var identity = fmt("%s|%s", endpoint.getMode().isInput() ? "in" : "out", key);
                if (seen.contains(identity))
                    return showValidateFailed(R.string.edit_vm_peripheral_duplicate_host);
                seen.add(identity);
            }
        }
        // A path-based serial backend without a path has nowhere to put the bytes. PTY is the
        // exception: its path is an optional convenience symlink. Two USB ACM ports on one
        // slot would be a guaranteed busy-refusal at boot, so it fails here instead.
        var usbSlots = new ArrayList<Integer>();
        for (var iter : requireNonNull(listSerialPorts.getItems())) {
            var port = new VMSerialConfig(iter.getValue());
            var backend = port.getBackend();
            if (backend.usesPath() && backend != SerialBackend.PTY && port.getPath().isEmpty())
                return showValidateFailed(R.string.edit_vm_serial_path_required);
            if (backend == SerialBackend.USB_ACM) {
                if (usbSlots.contains(port.getUsbSlot()))
                    return showValidateFailed(R.string.edit_vm_serial_slot_duplicate);
                usbSlots.add(port.getUsbSlot());
            }
        }
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        config.item.set("peripherals", requireNonNull(listPeripherals.getItems()));
        config.item.set(VMSerialConfig.KEY, requireNonNull(listSerialPorts.getItems()));
    }

    /** The unsaved rows, shaped like a VM config so {@link VMPeripheralConfig} can read them. */
    @NonNull
    private DataItem wrap() {
        var wrapper = DataItem.newObject();
        wrapper.set("peripherals", requireNonNull(listPeripherals.getItems()));
        return wrapper;
    }
}
