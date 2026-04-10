package cn.classfun.droidvm.lib.store.vm;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

import cn.classfun.droidvm.lib.store.base.DataStore;

public final class VMStore extends DataStore<VMConfig> {
    public VMStore() {
        super();
    }

    @SuppressWarnings("unused")
    public VMStore(@NonNull JSONObject obj) {
        super(obj);
    }

    @SuppressWarnings("unused")
    public VMStore(@NonNull File file) {
        super(file);
    }

    @SuppressWarnings("unused")
    public VMStore(@NonNull Context context) {
        super(context);
    }

    @NonNull
    @Override
    protected VMConfig create() {
        return new VMConfig();
    }

    @NonNull
    @Override
    protected VMConfig create(@NonNull JSONObject obj) throws JSONException {
        return new VMConfig(obj);
    }

    @NonNull
    @Override
    protected DataStore<VMConfig> createEmpty() {
        return new VMStore();
    }

    @NonNull
    @Override
    protected String getTypeName() {
        return "vms";
    }
}
