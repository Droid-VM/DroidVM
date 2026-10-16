// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import cn.classfun.droidvm.lib.store.base.DataItem;

/** Converts an explicit baseline/selection pair into the native session protocol.
 * Both inputs must describe the same persisted version; never infer permission
 * to override from a newly loaded baseline after the user made their selection. */
public final class VmStartPlan {
    public final List<Integer> readonlyDisks;
    public final boolean pseudoUnprotected;

    private VmStartPlan(List<Integer> disks, boolean pseudo) {
        readonlyDisks = List.copyOf(disks);
        pseudoUnprotected = pseudo;
    }

    public JSONObject overrides() throws JSONException {
        var result = new JSONObject();
        if (!readonlyDisks.isEmpty()) {
            var slots = new JSONArray();
            for (int index : readonlyDisks) slots.put(index);
            result.put("readonly_disks", slots);
        }
        if (pseudoUnprotected) result.put("pseudo_unprotected", true);
        return result;
    }

    public static VmStartPlan between(DataItem baseline, DataItem selected) throws IOException {
        if (!baseline.is(DataItem.Type.OBJECT) || !selected.is(DataItem.Type.OBJECT))
            throw new IOException("Invalid VM start configuration");
        var slots = new ArrayList<Integer>();
        boolean pseudo = false;
        var keys = new HashSet<>(baseline.asObject().keySet());
        keys.addAll(selected.asObject().keySet());
        for (var key : keys) {
            var before = baseline.opt(key, null);
            var after = selected.opt(key, null);
            if (Objects.equals(before, after)) continue;
            if (key.equals("protected_vm") && after != null && after.is(DataItem.Type.STRING)
                && "pseudo_unprotected".equals(after.asString())) {
                pseudo = true;
            } else if (key.equals("disks")) {
                if (before == null || after == null || !before.is(DataItem.Type.ARRAY) || !after.is(DataItem.Type.ARRAY)
                    || before.asArray().size() != after.asArray().size()) throw changed();
                for (int i = 0; i < before.asArray().size(); i++) {
                    var oldDisk = before.get(i);
                    var newDisk = after.get(i);
                    if (oldDisk.equals(newDisk)) continue;
                    if (!oldDisk.is(DataItem.Type.OBJECT) || !newDisk.is(DataItem.Type.OBJECT)) throw changed();
                    var diskKeys = new HashSet<>(oldDisk.asObject().keySet());
                    diskKeys.addAll(newDisk.asObject().keySet());
                    for (var diskKey : diskKeys) {
                        if (Objects.equals(oldDisk.opt(diskKey, null), newDisk.opt(diskKey, null))) continue;
                        var readOnly = newDisk.opt(diskKey, null);
                        if (!diskKey.equals("readonly") || readOnly == null || !readOnly.is(DataItem.Type.BOOLEAN)
                            || !readOnly.asBoolean()) throw changed();
                    }
                    slots.add(i);
                }
            } else throw changed();
        }
        return new VmStartPlan(slots, pseudo);
    }

    private static IOException changed() {
        return new IOException("VM configuration differs from the confirmed baseline; reload before starting");
    }
}
