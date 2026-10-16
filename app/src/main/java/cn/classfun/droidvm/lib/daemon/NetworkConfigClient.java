// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Worker-only network edits over the existing authenticated transport. */
public final class NetworkConfigClient {
    public static final class StartUnknown extends IOException {
        StartUnknown(Exception cause) { super("Network start result unknown; reload state before retrying", cause); }
    }
    private final VmConfigClient.Rpc rpc;
    public NetworkConfigClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }

    private JSONObject call(String command, JSONObject request) throws IOException, JSONException {
        var result = rpc.request(request.put("command", command));
        if (result == null || !(result.opt("success") instanceof Boolean))
            throw new IOException("Missing or invalid network configuration response");
        if (!result.getBoolean("success")) throw new VmConfigClient.RemoteFailure(result);
        return result;
    }

    /** Only an explicitly legacy daemon permits the local-file fallback. */
    public boolean supported() throws IOException, JSONException {
        var caps = call("capabilities", new JSONObject());
        if ("compare-config-v1".equals(caps.optString("network_config_update"))) return true;
        if (caps.has("network_config_update") || (caps.has("network_config_persistence")
                && !Boolean.FALSE.equals(caps.opt("network_config_persistence")))
                || caps.has("network_config_events"))
            throw new IOException("Network configuration API is incompatible; reopen after updating the daemon");
        return false;
    }

    /** Advisory list for name/address validation, never an update baseline. */
    public JSONObject planningDocument() throws IOException, JSONException {
        return new JSONObject().put("networks", call("network_list", new JSONObject()).getJSONArray("data"));
    }

    public EditSession edit(String id) throws IOException, JSONException {
        UUID.fromString(id);
        var baseline = call("network_config_get", new JSONObject().put("network_id", id)).getJSONObject("config");
        if (!id.equals(baseline.getString("id"))) throw new IOException("Network configuration identity mismatch");
        return new EditSession(id, baseline.toString());
    }

    public EditSession create(String id) {
        UUID.fromString(id);
        return new EditSession(id, null);
    }

    /** Start the service's current persisted network by identity, without saving UI state. */
    public void start(String id) throws IOException, JSONException {
        UUID.fromString(id);
        try {
            var result = call("network_start", new JSONObject().put("network_id", id));
            if (!id.equals(result.getString("network_id"))
                    || !"running".equalsIgnoreCase(result.getString("state")))
                throw new IOException("Invalid network start acknowledgement");
        } catch (VmConfigClient.RemoteFailure failure) {
            if ("UNKNOWN".equals(failure.code) || "INTERNAL".equals(failure.code))
                throw new StartUnknown(failure);
            throw failure;
        } catch (IOException | JSONException failure) { throw new StartUnknown(failure); }
    }

    public final class EditSession {
        private final String id, baseline;
        private boolean reloadRequired;
        private EditSession(String id, String baseline) { this.id = id; this.baseline = baseline; }
        public JSONObject configuration() throws JSONException {
            return baseline == null ? null : new JSONObject(baseline);
        }
        public synchronized boolean requiresReload() { return reloadRequired; }
        public synchronized void save(JSONObject config) throws IOException, JSONException {
            if (reloadRequired) throw new IOException("Reload network state before saving again");
            var detached = new JSONObject(config.toString());
            if (!id.equals(detached.getString("id"))) throw new IOException("Network identity changed");
            if (detached.toString().getBytes(StandardCharsets.UTF_8).length > (256 << 10))
                throw new IOException("Network configuration exceeds edit limit");
            var request = new JSONObject().put("config", detached);
            if (baseline != null) request.put("expected", new JSONObject(baseline));
            reloadRequired = true;
            try {
                var result = call(baseline == null ? "network_create" : "network_config_update", request);
                if (!id.equals(result.getString("network_id"))) throw new IOException("Network save identity mismatch");
            } catch (VmConfigClient.RemoteFailure failure) {
                if ("INVALID_ARGUMENT".equals(failure.code) || "IO_ERROR".equals(failure.code)
                        || "RESOURCE_EXHAUSTED".equals(failure.code)) reloadRequired = false;
                if ("UNKNOWN".equals(failure.code) || "INTERNAL".equals(failure.code))
                    throw new VmConfigClient.CommitUnknown(failure);
                throw failure;
            } catch (IOException | JSONException failure) {
                throw new VmConfigClient.CommitUnknown(failure);
            }
        }
    }
}
