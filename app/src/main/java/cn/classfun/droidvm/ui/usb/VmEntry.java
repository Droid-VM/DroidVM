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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.store.vm.VMXhciConfig;

/**
 * One row of {@code vm_list}: enough to name a rule's target and to say whether it could take
 * a device right now (only a running VM does).
 *
 * <p>What controllers that VM has is deliberately not read from here, although the row carries
 * the whole config: the daemon's copy of a config is loaded once at daemon start and replaced
 * only when a VM is created or started, so it answers with the controllers a VM had rather than
 * the ones the editor gave it. That question goes to vms.json -- see {@link #controllersOf}.</p>
 */
public final class VmEntry {
    public final String id;
    public final String name;
    @Nullable
    public final VMState state;
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
     * Which xHCI controllers each VM has, by VM id, read from vms.json.
     *
     * <p>This is what decides whether a rule's target still exists and what a device may be sent
     * to, and only the config file can say: a controller added in the editor would read as
     * missing in the daemon's copy, and one deleted there would go on reading as a healthy
     * target until the VM was next started -- wrong in both directions, on the two surfaces that
     * report a dangling target.</p>
     */
    @NonNull
    public static Map<String, List<String>> controllersOf(@NonNull Context context) {
        var store = new VMStore();
        store.load(context);
        var out = new HashMap<String, List<String>>();
        store.forEach((id, config) -> {
            if (id == null) return;
            var ids = new ArrayList<String>();
            for (var controller : VMXhciConfig.listControllers(config.item))
                if (!controller.getControllerId().isEmpty())
                    ids.add(controller.getControllerId());
            out.put(id.toString(), ids);
        });
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
