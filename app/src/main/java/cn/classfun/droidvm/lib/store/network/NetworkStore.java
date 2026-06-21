package cn.classfun.droidvm.lib.store.network;

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
                    Log.w(TAG, "Skipping network config with unsupported schema: "
                        + entry.optString("name"));
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
                    Log.w(TAG, "Skipping legacy network that failed migration/validation: "
                        + entry.optString("name"), e);
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
