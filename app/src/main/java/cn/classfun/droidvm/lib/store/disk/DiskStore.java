package cn.classfun.droidvm.lib.store.disk;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

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
}
