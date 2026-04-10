package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataConfig;

public class VMConfig extends DataConfig {
    public VMConfig() {
        setId(UUID.randomUUID());
    }

    public VMConfig(@NonNull JSONObject obj) throws JSONException {
        item.set(obj);
    }
}
