// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import org.json.JSONArray;
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
                || caps.has("network_config_events") || caps.has("network_config_import") || caps.has("network_config_document")
                || caps.has("network_config_delete") || caps.has("configuration_recover"))
            throw new IOException("Network configuration API is incompatible; reopen after updating the daemon");
        return false;
    }

    /** A fixed paginated configuration view, without runtime projection fields. */
    public JSONObject planningDocument() throws IOException, JSONException {
        var caps = call("capabilities", new JSONObject());
        if (caps.has("network_config_document")) {
            if (!"base64-json-chunks-v1".equals(caps.optString("network_config_document")))
                throw new IOException("Unsupported network document encoding");
            var document = new JSONObject(new VmConfigClient(rpc)
                .readDocument("network_config_document", "networks", 8 << 20).json);
            var rows = document.getJSONArray("networks");
            if (rows.length() > 1024) throw new IOException("Too many networks in snapshot");
            var ids = new HashSet<String>();
            for (int i = 0; i < rows.length(); i++)
                if (!ids.add(UUID.fromString(rows.getJSONObject(i).getString("id")).toString()))
                    throw new IOException("Repeated network in snapshot");
            return document;
        }
        var page = call("network_config_snapshot", new JSONObject().put("max_bytes", 1 << 20));
        String id = page.getString("snapshot_id");
        try {
            String epoch = page.getString("epoch"), cursor = page.getString("cursor");
            int total = page.getInt("total");
            if (id.isEmpty() || epoch.isEmpty() || cursor.isEmpty() || total < 0 || total > 1024)
                throw new IOException("Invalid network snapshot metadata");
            var rows = new JSONArray();
            var ids = new HashSet<String>();
            long bytes = 0;
            for (;;) {
                if (!id.equals(page.getString("snapshot_id")) || !epoch.equals(page.getString("epoch"))
                        || !cursor.equals(page.getString("cursor")) || total != page.getInt("total"))
                    throw new IOException("Network snapshot changed between pages");
                var items = page.getJSONArray("items");
                if (items.length() > total - rows.length() || (items.length() == 0 && rows.length() < total))
                    throw new IOException("Invalid network snapshot page length");
                for (int i = 0; i < items.length(); i++) {
                    var config = items.getJSONObject(i);
                    String key = UUID.fromString(config.getString("id")).toString();
                    if (!ids.add(key)) throw new IOException("Repeated network in snapshot");
                    bytes += config.toString().getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > (8 << 20)) throw new IOException("Network snapshot exceeds storage capacity");
                    rows.put(new JSONObject(config.toString()));
                }
                if (!page.has("next_offset")) throw new IOException("Missing network snapshot continuation");
                if (page.isNull("next_offset")) {
                    if (rows.length() != total) throw new IOException("Incomplete network snapshot");
                    return new JSONObject().put("networks", rows);
                }
                if (page.getInt("next_offset") != rows.length() || rows.length() >= total)
                    throw new IOException("Invalid network snapshot continuation");
                page = call("resource_snapshot_page", new JSONObject().put("snapshot_id", id)
                    .put("offset", rows.length()).put("max_bytes", 1 << 20));
            }
        } finally {
            try { call("resource_snapshot_release", new JSONObject().put("snapshot_id", id)); }
            catch (IOException | JSONException ignored) { /* Fixed expiry reclaims disconnected views. */ }
        }
    }

    public ImportSession importSession() throws IOException, JSONException {
        var caps = call("capabilities", new JSONObject());
        if (!"conditional-chunks-v1".equals(caps.optString("network_config_import"))
                || !"invalidation-v1".equals(caps.optString("network_config_events")))
            throw new IOException("Atomic network import API is unavailable");
        return new ImportSession(planningDocument().toString());
    }

    public final class ImportSession {
        private final String baseline;
        private boolean submitted;
        private ImportSession(String baseline) { this.baseline = baseline; }
        public JSONObject document() throws JSONException { return new JSONObject(baseline); }
        public synchronized void merge(JSONArray imported) throws IOException, JSONException {
            if (submitted) throw new IOException("Reload network state before importing again");
            if (imported.length() > 1024) throw new IOException("Too many imported networks");
            var current = new HashMap<String, JSONObject>();
            var rows = document().getJSONArray("networks");
            for (int i = 0; i < rows.length(); i++) {
                var row = rows.getJSONObject(i);
                current.put(UUID.fromString(row.getString("id")).toString(), row);
            }
            var ids = new HashSet<String>();
            var ordered = new JSONArray();
            var operations = new JSONArray();
            for (int i = 0; i < imported.length(); i++) {
                var config = new JSONObject(imported.getJSONObject(i).toString());
                String id = UUID.fromString(config.getString("id")).toString();
                if (!ids.add(id)) throw new IOException("Repeated network import ID");
                if (config.toString().getBytes(StandardCharsets.UTF_8).length > (256 << 10))
                    throw new IOException("Network configuration exceeds edit limit");
                ordered.put(id);
                operations.put(new JSONObject().put("config", config)
                    .put("expected", current.containsKey(id) ? current.get(id) : JSONObject.NULL));
            }
            if (operations.length() == 0) return;
            byte[] bytes = new JSONObject().put("operations", operations).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > (17 << 20)) throw new IOException("Network import exceeds upload limit");
            submitted = true;
            final JSONObject result;
            try { result = new VmConfigClient(rpc).upload("network_config_import", new JSONObject(), bytes); }
            catch (VmConfigClient.RemoteFailure failure) {
                if ("INTERNAL".equals(failure.code) || "UNKNOWN".equals(failure.code))
                    throw new VmConfigClient.CommitUnknown(failure);
                throw failure;
            }
            try {
                var saved = result.getJSONArray("network_ids");
                if (saved.length() != ordered.length()) throw new IOException("Incomplete network import acknowledgement");
                for (int i = 0; i < saved.length(); i++)
                    if (!ordered.getString(i).equals(saved.getString(i))) throw new IOException("Network import identity mismatch");
            } catch (IOException | JSONException failure) { throw new VmConfigClient.CommitUnknown(failure); }
        }
    }

    /** Capture both confirmations before displaying deletion to the user. Network must be stopped. */
    public DeleteSession deleteSession(String id) throws IOException, JSONException {
        String canonical = UUID.fromString(id).toString();
        var caps = call("capabilities", new JSONObject());
        if (!"revisioned-stopped-chunks-v1".equals(caps.optString("network_config_delete")))
            throw new IOException("Coordinated network deletion API is unavailable");
        var vms = new VmConfigClient(rpc).read();
        var rows = planningDocument().getJSONArray("networks");
        for (int i = 0; i < rows.length(); i++) {
            var row = rows.getJSONObject(i);
            if (canonical.equals(UUID.fromString(row.getString("id")).toString()))
                return new DeleteSession(canonical, row.toString(), vms);
        }
        throw new IOException("Network is not in the confirmation snapshot");
    }

    public final class DeleteSession {
        private final String id, baseline;
        private final VmConfigClient.Snapshot vms;
        private boolean submitted;
        private DeleteSession(String id, String baseline, VmConfigClient.Snapshot vms) {
            this.id = id; this.baseline = baseline; this.vms = vms;
        }
        public JSONObject configuration() throws JSONException { return new JSONObject(baseline); }
        public JSONObject vmDocument() throws JSONException { return vms.document(); }
        public synchronized VmConfigClient.Version delete() throws IOException, JSONException {
            if (submitted) throw new IOException("Reload network and VM state before deleting again");
            byte[] bytes = new JSONObject().put("expected", new JSONObject(baseline)).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > (9 << 20)) throw new IOException("Network deletion confirmation exceeds upload limit");
            submitted = true;
            final JSONObject response;
            try {
                response = new VmConfigClient(rpc).upload("network_config_delete", new JSONObject().put("revision", vms.revision), bytes);
            } catch (VmConfigClient.RemoteFailure failure) {
                if ("INTERNAL".equals(failure.code) || "UNKNOWN".equals(failure.code))
                    throw new VmConfigClient.CommitUnknown(failure);
                throw failure;
            }
            try {
                var version = new VmConfigClient.Version(response);
                if (!id.equals(response.getString("network_id")) || !version.persisted || version.durabilityUncertain)
                    throw new IOException("Network deletion acknowledgement is incomplete");
                return version;
            } catch (IOException | JSONException failure) { throw new VmConfigClient.CommitUnknown(failure); }
        }
    }

    /** Explicit recovery of a server-side decision; never resubmits the deletion. */
    public void recoverConfiguration() throws IOException, JSONException {
        var caps = call("capabilities", new JSONObject());
        if (!"redo-v1".equals(caps.optString("configuration_recover")))
            throw new IOException("Configuration recovery API is unavailable");
        call("configuration_recover", new JSONObject());
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
