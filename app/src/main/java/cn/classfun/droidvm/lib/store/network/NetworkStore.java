package cn.classfun.droidvm.lib.store.network;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

import cn.classfun.droidvm.lib.store.base.DataStore;

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
