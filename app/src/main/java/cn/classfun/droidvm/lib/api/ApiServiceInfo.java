package cn.classfun.droidvm.lib.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class ApiServiceInfo implements JSONSerialize {
    private final String id;
    private final String path;
    private final Map<String, String> name;

    public ApiServiceInfo(
        @NonNull String id,
        @NonNull JSONObject json
    ) throws JSONException {
        this.id = id;
        this.path = json.getString("path");
        this.name = JsonUtils.objectToStringMap(json, "name");
    }

    @NonNull
    public static ApiServiceInfo create(
        @NonNull String id,
        @NonNull JSONObject json
    ) throws JSONException {
        return new ApiServiceInfo(id, json);
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getId() {
        return id;
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getPath() {
        return path;
    }

    @NonNull
    @SuppressWarnings("unused")
    public Map<String, String> getNames() {
        return name;
    }

    @Nullable
    @SuppressWarnings("unused")
    public String getName(@NonNull String lang) {
        return name.get(lang);
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getName() {
        return JsonUtils.getMultiLanguageString(name);
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var names = new JSONObject();
        for (var entry : name.entrySet())
            names.put(entry.getKey(), entry.getValue());
        var json = new JSONObject();
        json.put("path", path);
        json.put("name", names);
        return json;
    }
}
