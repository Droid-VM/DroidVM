package cn.classfun.droidvm.lib.store.network;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

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
                if (!NetworkConfig.isSupportedSchema(entry)) {
                    Log.w(TAG, "Skipping network config with unsupported schema: "
                        + entry.optString("name"));
                    return;
                }
                store.addObject(entry);
            });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load network configs", e);
            store.clear();
            return false;
        }
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
