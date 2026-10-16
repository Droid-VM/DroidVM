// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import java.io.IOException;
import java.util.HashSet;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Worker-only registry reads. Read capability never authorizes local writes. */
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
