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
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * One row of {@code vm_list}: enough to name a rule's target and to say whether it could take
 * a device right now (only a running VM does).
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
