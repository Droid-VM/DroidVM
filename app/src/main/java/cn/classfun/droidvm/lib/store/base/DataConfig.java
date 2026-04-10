package cn.classfun.droidvm.lib.store.base;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public abstract class DataConfig implements JSONSerialize {
    public final DataItem item = DataItem.newObject();

    public final UUID getId() {
        return UUID.fromString(item.optString("id", ""));
    }

    public final void setId(@NonNull UUID id) {
        item.set("id", id);
    }

    public final void setId(@NonNull String id) {
        try {
            setId(UUID.fromString(id));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public final String getName() {
        return item.optString("name", null);
    }

    public final void setName(String name) {
        item.set("name", name);
    }

    @NonNull
    @Override
    public final JSONObject toJson() throws JSONException {
        return item.toJson();
    }

    @NonNull
    @Override
    public final String toString() {
        return item.toString();
    }
}
