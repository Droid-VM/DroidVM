package cn.classfun.droidvm.daemon.server;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class ClientRequest {
    private final UUID id;
    private final String command;
    private final JSONObject params;
    private final ClientResponse response;
    private final ClientHandler client;

    public ClientRequest(@NonNull ClientHandler client, @NonNull JSONObject params) throws JSONException {
        this.client = client;
        this.params = params;
        this.id = UUID.fromString(params.getString("request_id"));
        this.command = params.getString("command");
        this.response = new ClientResponse(this);
    }

    @NonNull
    public UUID getId() {
        return id;
    }

    @NonNull
    public String getCommand() {
        return command;
    }

    @NonNull
    public JSONObject getParams() {
        return params;
    }

    @NonNull
    public ClientResponse getResponse() {
        return response;
    }

    @NonNull
    public ClientHandler getClient() {
        return client;
    }

    @NonNull
    public ServerContext getContext() {
        return client.getServer().getContext();
    }

    @Nullable
    public RequestHandler findHandler() {
        return client.getServer().handlers.find(command);
    }

    @NonNull
    public JSONObject res() {
        return getResponse().json;
    }
}
