package cn.classfun.droidvm.lib.crypt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;

public final class HashItem implements JSONSerialize {
    public final @NonNull String file;
    public final @Nullable String source;
    public final @NonNull String sha256;

    public HashItem(@NonNull String file, @Nullable String source, @NonNull String sha256) {
        this.file = file;
        this.source = source;
        this.sha256 = sha256;
    }

    @SuppressWarnings("unused")
    public HashItem(@NonNull String file, @NonNull String sha256) {
        this(file, null, sha256);
    }

    @SuppressWarnings("unused")
    public HashItem(@NonNull JSONObject obj) {
        this(
            obj.optString("file", null),
            obj.optString("source", null),
            obj.optString("sha256", null)
        );
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        obj.put("file", file);
        obj.put("sha256", sha256);
        if (source != null)
            obj.put("source", source);
        return obj;
    }
}
