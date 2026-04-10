package cn.classfun.droidvm.lib.store.base;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

public interface JSONSerialize {
    @NonNull
    JSONObject toJson() throws JSONException;
}
