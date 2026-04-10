package cn.classfun.droidvm.lib.daemon;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;

public final class RequestContext {
    private static final String TAG = "RequestContext";
    private final DaemonConnection connection;
    private DaemonConnection.OnUnsuccessful onUnsuccessful = null;
    private DaemonConnection.OnResponse onResponse = null;
    private DaemonConnection.OnError onError = null;
    private JSONObject data = new JSONObject();

    RequestContext(DaemonConnection connection) {
        this.connection = connection;
    }

    @NonNull
    public RequestContext onUnsuccessful(@Nullable DaemonConnection.OnUnsuccessful callback) {
        this.onUnsuccessful = callback;
        return this;
    }

    @NonNull
    public RequestContext onResponse(@Nullable DaemonConnection.OnResponse callback) {
        this.onResponse = callback;
        return this;
    }

    @NonNull
    public RequestContext onError(@Nullable DaemonConnection.OnError callback) {
        this.onError = callback;
        return this;
    }

    @NonNull
    @SuppressWarnings("unused")
    public RequestContext set(@NonNull JSONObject data) {
        this.data = data;
        return this;
    }

    @NonNull
    @SuppressWarnings("unused")
    public RequestContext put(@NonNull String key, @Nullable Object value) {
        try {
            if (value instanceof JSONSerialize) {
                var obj = (JSONSerialize) value;
                this.data.put(key, obj.toJson());
            } else {
                this.data.put(key, value);
            }
        } catch (JSONException ignored) {
        }
        return this;
    }

    @NonNull
    @SuppressWarnings("unused")
    public RequestContext copy(@Nullable JSONObject obj, @NonNull String key) {
        try {
            if (obj != null && obj.has(key))
                this.data.put(key, obj.get(key));
        } catch (JSONException ignored) {
        }
        return this;
    }

    public void invoke() {
        connection.executor.submit(() -> {
            try {
                var resp = connection.request(data);
                if (onUnsuccessful != null && !resp.optBoolean("success", true)) {
                    onUnsuccessful.onUnsuccessful(resp);
                    return;
                }
                if (onResponse != null) onResponse.onResponse(resp);
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Async request failed", e);
                if (onError != null) onError.onError(e);
            }
        });
    }
}
