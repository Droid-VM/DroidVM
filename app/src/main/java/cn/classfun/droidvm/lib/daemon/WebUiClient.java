// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Worker-only local WebUI management. No file fallback and no mutation retry. */
public final class WebUiClient {
    public static final class Unsupported extends IOException {}
    public static final class ReloadRequired extends IOException {}
    private final VmConfigClient.Rpc rpc;
    private boolean reloadRequired;
    public WebUiClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }

    public static final class Settings {
        public final boolean enabled;
        public final int port;
        public final String scope;
        public final List<String> publicOrigins;
        public final boolean lanAutoLogin, lanAutoLoginSupported;
        public Settings(boolean enabled, int port, String scope, List<String> origins) {
            this(enabled, port, scope, origins, false, false);
        }
        public Settings(boolean enabled, int port, String scope, List<String> origins, boolean lanAutoLogin) {
            this(enabled, port, scope, origins, lanAutoLogin, true);
        }
        private Settings(boolean enabled, int port, String scope, List<String> origins, boolean lanAutoLogin, boolean supported) {
            if (port < 1024 || port > 65535 || !("lan".equals(scope) || "all".equals(scope)) || origins.size() > 8)
                throw new IllegalArgumentException("Invalid WebUI settings");
            for (String origin : origins) validateOrigin(origin);
            this.enabled = enabled; this.port = port; this.scope = scope;
            this.publicOrigins = Collections.unmodifiableList(new ArrayList<>(origins));
            this.lanAutoLogin = lanAutoLogin; this.lanAutoLoginSupported = supported;
        }
        public Settings withConnection(boolean enabled, int port, String scope, List<String> origins) {
            return new Settings(enabled, port, scope, origins, lanAutoLogin, lanAutoLoginSupported);
        }
        public Settings withLanAutoLogin(boolean value) {
            if (!lanAutoLoginSupported) throw new IllegalStateException("LAN auto login is unsupported");
            return new Settings(enabled, port, scope, publicOrigins, value);
        }
        public JSONObject json() throws JSONException {
            JSONObject result = new JSONObject().put("enabled", enabled).put("port", port).put("scope", scope)
                .put("publicOrigins", new JSONArray(publicOrigins));
            if (lanAutoLoginSupported) result.put("lanAutoLogin", lanAutoLogin);
            return result;
        }
        static Settings parse(JSONObject value) throws JSONException, IOException {
            Object port = value.get("port");
            boolean supported = value.has("lanAutoLogin");
            if (value.length() != (supported ? 5 : 4) || !(value.get("enabled") instanceof Boolean)
                    || (supported && !(value.get("lanAutoLogin") instanceof Boolean))
                    || !(port instanceof Integer || port instanceof Long) || !(value.get("scope") instanceof String))
                throw new IOException("Invalid settings response");
            long number = ((Number) port).longValue();
            if (number < 1024 || number > 65535) throw new IOException("Invalid port response");
            try { return new Settings(value.getBoolean("enabled"), (int) number, value.getString("scope"),
                strings(value.getJSONArray("publicOrigins"), 8, 262), supported && value.getBoolean("lanAutoLogin"), supported); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid settings response", error); }
        }
    }
    public static final class Status {
        public final Settings settings;
        public final boolean running;
        public final String state, error;
        public final List<String> addresses, sessions;
        Status(JSONObject value) throws JSONException, IOException {
            settings = Settings.parse(value.getJSONObject("settings"));
            if (!(value.get("running") instanceof Boolean) || !(value.get("state") instanceof String)) throw new IOException("Invalid status response");
            running = value.getBoolean("running"); state = value.getString("state");
            if (!List.of("disabled", "running", "local_only", "error").contains(state)) throw new IOException("Unknown WebUI state");
            Object problem = value.get("error");
            if (problem != JSONObject.NULL && !(problem instanceof String)) throw new IOException("Invalid WebUI error");
            error = problem == JSONObject.NULL ? "" : (String) problem;
            if (!List.of("", "discovery_failed", "listen_failed", "configuration_write_failed").contains(error)) throw new IOException("Unknown WebUI error");
            addresses = strings(value.getJSONArray("addresses"), 64, 262);
            for (String address : addresses) {
                try { validateOrigin(address); } catch (IllegalArgumentException e) { throw new IOException("Invalid access address", e); }
            }
            sessions = strings(value.getJSONArray("sessions"), 128, 128);
            if (new java.util.HashSet<>(sessions).size() != sessions.size()) throw new IOException("Repeated session");
        }
    }
    private static List<String> strings(JSONArray array, int count, int length) throws JSONException, IOException {
        if (array.length() > count) throw new IOException("List exceeds contract limit");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > length
                    || ((String) value).chars().anyMatch(c -> c <= 32 || c >= 127)) throw new IOException("Invalid list entry");
            result.add((String) value);
        }
        return Collections.unmodifiableList(result);
    }
    private static void validateOrigin(String origin) {
        try {
            URI uri = new URI(origin);
            if (origin.length() > 262 || !"http".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !"".equals(uri.getRawPath()) || uri.getPort() == 0 || uri.getPort() > 65535
                    || !origin.equals("http://" + uri.getRawAuthority())) throw new IllegalArgumentException("Invalid HTTP origin");
        } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Invalid HTTP origin", e); }
    }
    private JSONObject call(String command, JSONObject request) throws IOException, JSONException {
        JSONObject result = rpc.request(request.put("command", command));
        if (result == null || !(result.opt("success") instanceof Boolean)) throw new IOException("Invalid WebUI response");
        if (!result.getBoolean("success")) throw new VmConfigClient.RemoteFailure(result);
        return result;
    }
    private void compatible() throws IOException, JSONException {
        JSONObject caps = call("capabilities", new JSONObject());
        if (!"http-session-v1".equals(caps.optString("webui"))) throw new Unsupported();
        List<String> commands = strings(caps.getJSONArray("commands"), 512, 128);
        if (!commands.containsAll(List.of("web_status", "web_configure", "web_reload", "web_pair", "web_revoke"))) throw new Unsupported();
    }
    private JSONObject mutate(String command, JSONObject request) throws IOException, JSONException {
        compatible(); // A restarted daemon may expose a different protocol.
        reloadRequired = true;
        try { return call(command, request); }
        catch (VmConfigClient.RemoteFailure failure) {
            reloadRequired = "CONFLICT".equals(failure.code) || "COMMIT_UNCERTAIN".equals(failure.code) || "UNKNOWN".equals(failure.code);
            throw failure;
        }
    }
    public synchronized boolean needsReload() { return reloadRequired; }
    public synchronized Status read(boolean reload) throws IOException, JSONException {
        compatible();
        Status status = new Status(call(reload ? "web_reload" : "web_status", new JSONObject()));
        if (reload) reloadRequired = false;
        if ("configuration_write_failed".equals(status.error)) reloadRequired = true;
        return status;
    }
    public synchronized Status configure(Settings expected, Settings next) throws IOException, JSONException {
        if (reloadRequired) throw new ReloadRequired();
        JSONObject request = new JSONObject().put("expected", expected.json()).put("settings", next.json());
        Status status = new Status(mutate("web_configure", request));
        if (next.lanAutoLoginSupported && (!status.settings.lanAutoLoginSupported
                || status.settings.lanAutoLogin != next.lanAutoLogin)) throw new IOException("LAN auto login result was not confirmed");
        reloadRequired = "configuration_write_failed".equals(status.error);
        return status;
    }
    public synchronized String pair() throws IOException, JSONException {
        if (reloadRequired) throw new ReloadRequired();
        Object value = mutate("web_pair", new JSONObject()).get("pairingToken");
        if (!(value instanceof String) || !((String) value).matches("[0-9a-f]{64}")) throw new IOException("Invalid pairing grant");
        reloadRequired = false;
        return (String) value;
    }
    public synchronized Status revoke(String id) throws IOException, JSONException {
        if (reloadRequired) throw new ReloadRequired();
        Status status = new Status(mutate("web_revoke", new JSONObject().put("session_id", id)));
        reloadRequired = "configuration_write_failed".equals(status.error);
        return status;
    }
}
