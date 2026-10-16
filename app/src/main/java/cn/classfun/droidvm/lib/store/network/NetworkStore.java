// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class NetworkStore extends DataStore<NetworkConfig> {
    /** Worker-only settings merge, committed as one native network batch. */
    public static void importPersisted(@NonNull Context context, @NonNull JSONObject document) throws Exception {
        document.getJSONArray("networks");
        var imported = new NetworkStore();
        if (!imported.load(document)) throw new java.io.IOException("Cannot read imported network configuration");
        var rows = new org.json.JSONArray();
        var configs = new java.util.ArrayList<NetworkConfig>();
        imported.forEach((id, config) -> configs.add(config));
        if (configs.size() != document.getJSONArray("networks").length())
            throw new java.io.IOException("Imported networks contain unsupported configurations");
        for (var config : configs) {
            NetworkConfigValidator.validate(config);
            rows.put(config.toJson());
        }
        var client = new cn.classfun.droidvm.lib.daemon.NetworkConfigClient(
            request -> cn.classfun.droidvm.lib.daemon.DaemonConnection.getInstance().request(request));
        if (client.supported()) client.importSession().merge(rows);
        else cn.classfun.droidvm.lib.store.base.NetworkConfigOwnership.legacyWrite(context.getFilesDir(), () -> {
            var current = new NetworkStore();
            if (!current.load(context)) throw new java.io.IOException("Cannot read current network configuration");
            imported.forEach((id, config) -> {
                if (current.findById(id) == null) current.add(config);
                else current.update(config);
            });
            if (!current.save(context)) throw new java.io.IOException("Cannot save imported network configuration");
        });
    }

    public NetworkStore() {
        super();
    }

    @SuppressWarnings("unused")
    public NetworkStore(@NonNull JSONObject obj) {
        super(obj);
    }

    @SuppressWarnings("unused")
    public NetworkStore(@NonNull File file) {
        super(file);
    }

    @SuppressWarnings("unused")
    public NetworkStore(@NonNull Context context) {
        super(context);
    }

    @Override
    protected boolean load(@NonNull DataStore<NetworkConfig> store, @NonNull JSONObject obj) {
        try {
            store.clear();
            JsonUtils.forEachArray(obj, getTypeName(), (JSONObject entry) -> {
                var migrated = NetworkConfig.migrate(entry);
                if (migrated == null) {
                    Log.w(TAG, fmt("Skipping network config with unsupported schema: %s", entry.optString("name")));
                    return;
                }
                if (migrated == entry) { // already current schema
                    store.addObject(entry);
                    return;
                }
                // A config we just upgraded from a legacy schema: only keep it
                // if the migrated result actually validates (e.g. a DHCP pool
                // offset that lands outside the network is rejected here).
                NetworkConfig cfg;
                try {
                    cfg = new NetworkConfig(migrated);
                    NetworkConfigValidator.validate(cfg);
                } catch (Exception e) {
                    Log.w(TAG, fmt("Skipping legacy network that failed migration/validation: %s", entry.optString("name")), e);
                    return;
                }
                store.add(cfg);
            });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load network configs", e);
            store.clear();
            return false;
        }
    }

    /** True if no other network (besides {@code exclude}) uses this bridge name. */
    public boolean isBridgeNameUnique(@NonNull String bridgeName, @Nullable UUID exclude) {
        for (var item : dataMap) {
            if (exclude != null && exclude.equals(item.getId())) continue;
            if (bridgeName.equals(item.getBridgeName())) return false;
        }
        return true;
    }

    @NonNull
    @Override
    protected NetworkConfig create() {
        return new NetworkConfig();
    }

    @NonNull
    @Override
    protected NetworkConfig create(@NonNull JSONObject obj) throws JSONException {
        return new NetworkConfig(obj);
    }

    @NonNull
    @Override
    protected DataStore<NetworkConfig> createEmpty() {
        return new NetworkStore();
    }

    @NonNull
    @Override
    protected String getTypeName() {
        return "networks";
    }
}
