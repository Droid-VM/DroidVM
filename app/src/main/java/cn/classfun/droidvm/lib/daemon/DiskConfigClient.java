// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Worker-only registry operations. Never writes image files or falls back on failed RPC. */
public final class DiskConfigClient {
    public static final int MAX_BYTES = 8 << 20;
    private final VmConfigClient.Rpc rpc;
    public DiskConfigClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }
    public static final class Snapshot extends VmConfigClient.Version {
        public final String epoch, cursor;
        private final String json;
        private Snapshot(VmConfigClient.Document document) throws JSONException, IOException {
            super(document.metadata);
            epoch = document.epoch; cursor = document.cursor; json = document.json;
        }
        public JSONObject document() throws JSONException { return new JSONObject(json); }
    }
    private JSONObject call(String command) throws IOException, JSONException {
        var response = rpc.request(new JSONObject().put("command", command));
        if (response == null || !(response.opt("success") instanceof Boolean))
            throw new IOException("Missing or invalid disk registry response");
        if (!response.getBoolean("success")) throw new VmConfigClient.RemoteFailure(response);
        return response;
    }
    /** False only when no native disk ownership capability is advertised. */
    public boolean readSupported() throws IOException, JSONException {
        var caps = call("capabilities");
        if (Boolean.TRUE.equals(caps.opt("disk_config_persistence"))
                && "base64-json-chunks-v1".equals(caps.optString("disk_config_snapshot"))
                && "invalidation-v1".equals(caps.optString("disk_config_events"))) return true;
        var keys = caps.keys();
        while (keys.hasNext()) if (keys.next().startsWith("disk_config_"))
            throw new IOException("Disk registry API is incompatible; update the daemon before continuing");
        return false;
    }
    public VmConfigClient.Version status() throws IOException, JSONException {
        return new VmConfigClient.Version(call("disk_config_status"));
    }
    public EditSession edit() throws IOException, JSONException {
        var caps = call("capabilities");
        if (!Boolean.TRUE.equals(caps.opt("disk_config_persistence"))
                || !"base64-json-chunks-v1".equals(caps.optString("disk_config_snapshot"))
                || !"invalidation-v1".equals(caps.optString("disk_config_events"))
                || !"revisioned-chunks-v1".equals(caps.optString("disk_config_upload")))
            throw new IOException("Disk registry editing API is unavailable");
        return new EditSession(read());
    }
    public final class EditSession {
        private final Snapshot baseline;
        private boolean submitted;
        private EditSession(Snapshot baseline) { this.baseline = baseline; }
        public JSONObject document() throws JSONException { return baseline.document(); }
        /** One complete replacement against the opening revision. Reopen after any submission. */
        public synchronized VmConfigClient.Version save(JSONObject document) throws IOException, JSONException {
            if (submitted) throw new IOException("Reload disk registry before saving again");
            document.getJSONArray("disks");
            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Disk registry exceeds upload limit");
            submitted = true;
            final JSONObject result;
            try {
                result = new VmConfigClient(rpc).upload("disk_config_upload", new JSONObject().put("revision", baseline.revision), bytes);
            } catch (VmConfigClient.RemoteFailure failure) {
                if ("INTERNAL".equals(failure.code) || "UNKNOWN".equals(failure.code))
                    throw new VmConfigClient.CommitUnknown(failure);
                throw failure;
            }
            try { return new VmConfigClient.Version(result); }
            catch (IOException | JSONException failure) { throw new VmConfigClient.CommitUnknown(failure); }
        }
    }
    public Snapshot read() throws IOException, JSONException {
        var document = new VmConfigClient(rpc).readDocument("disk_config_snapshot", "disks", MAX_BYTES);
        var rows = new JSONObject(document.json).getJSONArray("disks");
        var ids = new HashSet<String>();
        for (int i = 0; i < rows.length(); i++) {
            var row = rows.getJSONObject(i);
            try {
                if (!ids.add(UUID.fromString(row.getString("id")).toString()))
                    throw new IOException("Repeated disk registry identity");
            } catch (IllegalArgumentException failure) { throw new IOException("Invalid disk registry identity", failure); }
            if (!(row.opt("name") instanceof String)) throw new IOException("Invalid disk registry name");
        }
        return new Snapshot(document);
    }
}
