package cn.classfun.droidvm.daemon.server;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class ClientResponse {
    private final ClientRequest request;
    public final JSONObject json = new JSONObject();

    public ClientResponse(@NonNull ClientRequest request) {
        this.request = request;
    }

    @NonNull
    public UUID getId() {
        return request.getId();
    }

    public void setException(@Nullable Exception exc) {
        try {
            if (exc == null) return;
            json.put("success", false);
            if (exc instanceof RequestException) {
                json.put("message", exc.getMessage());
            } else {
                json.put("message", "internal error");
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    public JSONObject pack() throws JSONException {
        json.put("type", "response");
        if (!json.has("success"))
            json.put("success", true);
        if (!json.has("request_id"))
            json.put("request_id", getId().toString());
        else if (!json.getString("request_id").equals(getId().toString()))
            throw new IllegalStateException("Response request_id does not match request");
        return json;
    }
}
