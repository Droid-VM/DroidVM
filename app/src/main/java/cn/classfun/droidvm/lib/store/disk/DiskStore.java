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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.base.DiskConfigOwnership;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.DiskConfigClient;

public final class DiskStore extends DataStore<DiskConfig> {
    /** Worker-only registration. Does not create, rewrite or remove image files. */
    public static void register(@NonNull Context context, @NonNull DiskConfig config) throws Exception {
        var client = new DiskConfigClient(request -> DaemonConnection.getInstance().request(request));
        if (client.readSupported()) {
            var session = client.edit();
            var document = session.document();
            var store = new DiskStore();
            if (!store.load(document)) throw new IOException("Cannot read disk registry");
            requireNewRegistration(store, config);
            // Append to the original JSON so untouched rows and root extensions stay exact.
            document.getJSONArray("disks").put(config.toJson());
            session.save(document);
        } else {
            DiskConfigOwnership.legacyWrite(context.getFilesDir(), () -> {
                var store = new DiskStore();
                if (!store.load(context)) throw new IOException("Cannot read disk registry");
                requireNewRegistration(store, config);
                store.add(config);
                if (!store.save(context)) throw new IOException("Cannot save disk registry");
            });
        }
    }

    /** Worker-only settings merge: replace rows by id, append new ones, one native commit. */
    public static void importPersisted(@NonNull Context context, @NonNull JSONObject document) throws Exception {
        var rows = document.getJSONArray("disks");
        var imported = new DiskStore();
        if (!imported.load(document)) throw new IOException("Cannot read imported disk registry");
        if (imported.size() != rows.length())
            throw new IOException("Imported disks contain unsupported entries");
        var client = new DiskConfigClient(request -> DaemonConnection.getInstance().request(request));
        if (client.readSupported()) {
            var session = client.edit();
            var current = session.document();
            var store = new DiskStore();
            if (!store.load(current)) throw new IOException("Cannot read disk registry");
            var disks = current.getJSONArray("disks");
            var index = new java.util.HashMap<String, Integer>();
            for (int i = 0; i < disks.length(); i++)
                index.put(disks.getJSONObject(i).optString("id"), i);
            var configs = new ArrayList<DiskConfig>();
            imported.forEach((id, config) -> configs.add(config));
            for (var config : configs) {
                var other = store.findByPath(config.getFullPath());
                if (other != null && !other.getId().equals(config.getId()))
                    throw new IOException("Disk is already registered: " + config.getFullPath());
                var at = index.get(config.getId().toString());
                // Replace in place so untouched rows and root extensions stay exact.
                if (at != null) disks.put(at, config.toJson());
                else disks.put(config.toJson());
            }
            session.save(current);
        } else {
            DiskConfigOwnership.legacyWrite(context.getFilesDir(), () -> {
                var store = new DiskStore();
                if (!store.load(context)) throw new IOException("Cannot read disk registry");
                imported.forEach((id, config) -> {
                    if (store.findById(id) == null) store.add(config);
                    else store.update(config);
                });
                if (!store.save(context)) throw new IOException("Cannot save disk registry");
            });
        }
    }

    static void requireNewRegistration(DiskStore store, DiskConfig config) throws IOException {
        if (store.findById(config.getId()) != null || store.findByPath(config.getFullPath()) != null)
            throw new IOException("Disk is already registered: " + config.getFullPath());
    }

    /** Worker-only planning snapshot. Never save this snapshot back to the legacy store. */
    public static DiskStore loadForPlanning(@NonNull Context context) {
        try {
            var client = new DiskConfigClient(request -> DaemonConnection.getInstance().request(request));
            var store = new DiskStore();
            boolean loaded = client.readSupported()
                ? store.load(client.read().document()) : store.load(context);
            if (!loaded) throw new IOException("Cannot read disk registry");
            return store;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read disk registry", error);
        }
    }

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
