package cn.classfun.droidvm.lib.store.network;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataConfig;

public class NetworkConfig extends DataConfig {
    public NetworkConfig() {
        setId(UUID.randomUUID());
    }

    public NetworkConfig(@NonNull JSONObject obj) throws JSONException {
        item.set(obj);
    }
}
