// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.disk;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataStore;

public final class DiskStore extends DataStore<DiskConfig> {
    public DiskStore() {
        super();
    }

    @SuppressWarnings("unused")
    public DiskStore(@NonNull JSONObject obj) {
        super(obj);
    }

    @SuppressWarnings("unused")
    public DiskStore(@NonNull File file) {
        super(file);
    }

    @SuppressWarnings("unused")
    public DiskStore(@NonNull Context context) {
        super(context);
    }

    @Override
    protected boolean shouldNameUnique() {
        return false;
    }

    @NonNull
    @Override
    protected DiskConfig create() {
        return new DiskConfig();
    }

    @NonNull
    @Override
    @SuppressWarnings("RedundantThrows")
    protected DiskConfig create(@NonNull JSONObject obj) throws JSONException {
        return new DiskConfig(obj);
    }

    @NonNull
    @Override
    protected DataStore<DiskConfig> createEmpty() {
        return new DiskStore();
    }

    @NonNull
    @Override
    protected String getTypeName() {
        return "disks";
    }

    @Nullable
    public DiskConfig findByPath(@NonNull String path) {
        for (int i = 0; i < size(); i++) {
            var cfg = get(i);
            if (path.equals(cfg.getFullPath()))
                return cfg;
        }
        return null;
    }

    // Overlay-tree helpers. Linear scans: the registry holds tens of entries, so scanning IS the
    // fast lookup, and unlike a cached index it can never go stale. Full-tree construction
    // (cycle guard, depth cap, flattening) lives in DiskTree.

    /** Whether any registered disk is an overlay of {@code id} - the disk is then locked. */
    public boolean hasChildren(@NonNull UUID id) {
        for (int i = 0; i < size(); i++)
            if (id.equals(get(i).getParentId())) return true;
        return false;
    }

    /** All direct overlays of {@code id}, in registry order. */
    @NonNull
    public List<DiskConfig> childrenOf(@NonNull UUID id) {
        var out = new ArrayList<DiskConfig>();
        for (int i = 0; i < size(); i++) {
            var cfg = get(i);
            if (id.equals(cfg.getParentId())) out.add(cfg);
        }
        return out;
    }

    /** The registered parent of {@code config}, or null (standalone / broken link). */
    @Nullable
    public DiskConfig parentOf(@NonNull DiskConfig config) {
        var parentId = config.getParentId();
        return parentId == null ? null : findById(parentId);
    }
}
