// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which tree nodes the user collapsed on the main disk list, persisted so the list keeps its
 * shape across sessions. Default is expanded - the point of the tree is seeing what stacks on
 * what - so only explicitly collapsed ids are stored. Dialog trees keep their own in-memory
 * state and don't touch this.
 */
public final class DiskTreeCollapse {
    private static final String PREFS_NAME = "droidvm_prefs";
    private static final String KEY = "disk_tree_collapsed";

    private DiskTreeCollapse() {
    }

    @NonNull
    public static Set<UUID> load(@NonNull Context context) {
        var raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY, null);
        var out = new HashSet<UUID>();
        if (raw != null) {
            for (var s : raw) {
                try {
                    out.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    public static void save(@NonNull Context context, @NonNull Set<UUID> collapsed) {
        var raw = new HashSet<String>();
        for (var id : collapsed) raw.add(id.toString());
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, raw).apply();
    }
}
