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
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
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
    private VMPeripheralEditAdapter adapter;

    public VMEditPeripheralTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        listPeripherals = view.findViewById(R.id.list_peripherals);
    }

    @Override
    public void initValue() {
        adapter = listPeripherals.setAdapter(VMPeripheralEditAdapter.class);
        adapter.setMicPermissionGate(parent.getRecordAudioPermission()::ensureThen);
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
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        config.item.set("peripherals", requireNonNull(listPeripherals.getItems()));
    }

    /** The unsaved rows, shaped like a VM config so {@link VMPeripheralConfig} can read them. */
    @NonNull
    private DataItem wrap() {
        var wrapper = DataItem.newObject();
        wrapper.set("peripherals", requireNonNull(listPeripherals.getItems()));
        return wrapper;
    }
}
