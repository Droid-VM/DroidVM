// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VMXhciConfig;

/**
 * One row of {@code vm_list}: enough to name a rule's target and to say whether it could take
 * a device right now (only a running VM does).
 */
public final class VmEntry {
    public final String id;
    public final String name;
    @Nullable
    public final VMState state;
    /**
     * The xHCI controllers this VM's config lists, in array order.
     *
     * <p>Kept so a rule that names a controller can be shown as what it is: one naming a
     * controller the VM does not have can never fire, and a target that reads like every other
     * would hide that.</p>
     */
    public final List<String> controllers;
    private final String rawState;

    private VmEntry(@NonNull JSONObject obj) {
        id = obj.optString("id", "");
        var given = obj.optString("name", "");
        name = given.isEmpty() ? id : given;
        rawState = obj.optString("state", "");
        VMState parsed = null;
        try {
            parsed = VMState.valueOf(rawState.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
        state = parsed;
        controllers = controllersOf(obj);
    }

    /** {@code vm_list} answers with the whole config, so the controller ids are already here. */
    @NonNull
    private static List<String> controllersOf(@NonNull JSONObject obj) {
        var out = new ArrayList<String>();
        var peripherals = obj.optJSONArray(VMXhciConfig.KEY_PERIPHERALS);
        if (peripherals == null) return out;
        for (int i = 0; i < peripherals.length(); i++) {
            var entry = peripherals.optJSONObject(i);
            if (entry == null) continue;
            if (!PeripheralType.XHCI_USB.name().equalsIgnoreCase(entry.optString("type", "")))
                continue;
            var id = entry.optString(VMXhciConfig.KEY_ID, "");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    @NonNull
    public static List<VmEntry> fromArray(@Nullable JSONArray arr) {
        var out = new ArrayList<VmEntry>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            var obj = arr.optJSONObject(i);
            if (obj != null && !obj.optString("id", "").isEmpty()) out.add(new VmEntry(obj));
        }
        return out;
    }

    /**
     * "name (state)" for a picker; the daemon's raw state text when it is one this build has
     * no word for, the bare name when it sent none.
     */
    @NonNull
    public String label(@NonNull Context context) {
        var stateText = state != null ? context.getString(state.getStringId()) : rawState;
        if (stateText.isEmpty()) return name;
        return context.getString(R.string.usb_rules_vm_label_fmt, name, stateText);
    }
}
