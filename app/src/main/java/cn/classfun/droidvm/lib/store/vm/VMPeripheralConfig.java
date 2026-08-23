// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.Enums;

/**
 * Wrapper over one entry of a VM config's "peripherals" array.
 *
 * <p>The host endpoint is stored as the stable descriptor {@code host_device}
 * ({@code "<TYPE>|<address>"}, see {@code HostAudioDevices}) rather than the numeric
 * AudioDeviceInfo id: those ids are handed out per boot and would point at a different -- or
 * absent -- endpoint the next time the phone comes up. {@code host_label} is only kept so the
 * row can still name a device that is currently unplugged.</p>
 */
public final class VMPeripheralConfig {
    public final DataItem item;

    public VMPeripheralConfig(@NonNull DataItem item) {
        this.item = item;
    }

    @NonNull
    public PeripheralType getType() {
        return Enums.optEnum(item, "type", PeripheralType.SPEAKER);
    }

    public void setType(@NonNull PeripheralType type) {
        item.set("type", type);
    }

    /** Stable host-device descriptor, or "" for "whatever the host would route to anyway". */
    @NonNull
    public String getHostDevice() {
        var key = item.optString("host_device", "");
        return key == null ? "" : key;
    }

    public void setHostDevice(@NonNull String key, @NonNull String label) {
        item.set("host_device", key);
        item.set("host_label", label);
    }

    /** Last known display name of the host device; may be stale or empty. */
    @NonNull
    public String getHostLabel() {
        var label = item.optString("host_label", "");
        return label == null ? "" : label;
    }

    /** Wraps every entry of {@code config}'s "peripherals" array, in order. */
    @NonNull
    public static List<VMPeripheralConfig> listOf(@NonNull DataItem config) {
        var out = new ArrayList<VMPeripheralConfig>();
        var arr = config.opt("peripherals", null);
        if (arr == null || !arr.is(DataItem.Type.ARRAY)) return out;
        for (var iter : arr)
            out.add(new VMPeripheralConfig(iter.getValue()));
        return out;
    }
}
