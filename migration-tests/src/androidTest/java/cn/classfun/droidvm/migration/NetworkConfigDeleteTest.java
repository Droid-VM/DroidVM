// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import cn.classfun.droidvm.lib.daemon.NetworkConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;

public final class NetworkConfigDeleteTest {
    private static final class Backend implements VmConfigClient.Rpc {
        final JSONObject network = new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "target")
            .put("extension", "x".repeat(60000));
        final ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        int commits, aborts, appends, releases, recoveries;
        String mode = "";
        Backend() throws JSONException {}
        JSONObject version() throws JSONException {
            return new JSONObject().put("revision", "new").put("persisted", true).put("recovered", false).put("durability_uncertain", false);
        }
        public JSONObject request(JSONObject request) throws IOException, JSONException {
            String command = request.getString("command");
            if ("capabilities".equals(command)) return new JSONObject().put("success", true)
                .put("network_config_delete", "unsupported".equals(mode) ? "future" : "revisioned-stop-chunks-v1")
                .put("configuration_recover", "redo-v1");
            if ("vm_config_snapshot".equals(command)) {
                byte[] bytes = "{\"vms\":[]}".getBytes(StandardCharsets.UTF_8);
                var metadata = version().put("revision", "observed").put("type", "metadata").put("encoding", "base64-json").put("bytes", bytes.length);
                return page("vms", new JSONArray().put(metadata).put(new JSONObject().put("type", "chunk").put("offset", 0)
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))));
            }
            if ("network_config_snapshot".equals(command)) return page("networks", new JSONArray().put(new JSONObject(network.toString())));
            if ("resource_snapshot_release".equals(command)) { releases++; return new JSONObject().put("success", true); }
            if ("network_config_delete_begin".equals(command)) {
                assertEquals("observed", request.getString("revision"));
                return new JSONObject().put("success", true).put("upload_id", "delete").put("chunk_bytes", 49152);
            }
            if ("network_config_delete_append".equals(command)) {
                appends++;
                if ("append".equals(mode)) throw new IOException("append failed");
                assertEquals(uploaded.size(), request.getInt("offset"));
                uploaded.write(Base64.decode(request.getString("data"), Base64.NO_WRAP));
                return new JSONObject().put("success", true).put("received", uploaded.size());
            }
            if ("network_config_delete_abort".equals(command)) { aborts++; return new JSONObject().put("success", true); }
            if ("network_config_delete_commit".equals(command)) {
                commits++;
                if ("lost".equals(mode)) throw new IOException("lost commit response");
                if ("CONFLICT".equals(mode) || "COMMIT_UNCERTAIN".equals(mode))
                    return new JSONObject().put("success", false).put("error", new JSONObject().put("code", mode));
                return version().put("success", true).put("network_id", "wrong".equals(mode) ? UUID.randomUUID().toString() : network.getString("id"));
            }
            if ("configuration_recover".equals(command)) { recoveries++; return new JSONObject().put("success", true); }
            throw new IOException("Unexpected command " + command);
        }
        JSONObject page(String id, JSONArray items) throws JSONException {
            return new JSONObject().put("success", true).put("snapshot_id", id).put("epoch", "epoch").put("cursor", "1")
                .put("total", items.length()).put("items", items).put("next_offset", JSONObject.NULL);
        }
    }
    @Test public void fixedConfirmationIsUploadedOnce() throws Exception {
        var backend = new Backend(); var client = new NetworkConfigClient(backend);
        var session = client.deleteSession(backend.network.getString("id"));
        session.configuration().put("name", "caller mutation");
        backend.network.put("name", "later server state");
        assertEquals("new", session.delete().revision);
        var payload = new JSONObject(backend.uploaded.toString(StandardCharsets.UTF_8.name()));
        assertEquals("target", payload.getJSONObject("expected").getString("name"));
        assertEquals(2, backend.appends); assertEquals(2, backend.releases);
        assertEquals(1, backend.commits); assertEquals(0, backend.aborts);
        assertThrows(IOException.class, session::delete);
        assertEquals(1, backend.commits);
    }
    @Test public void unknownConflictAndUncertainNeverRetryOrAbort() throws Exception {
        for (String mode : new String[]{"lost", "wrong", "CONFLICT", "COMMIT_UNCERTAIN"}) {
            var backend = new Backend(); backend.mode = mode;
            var session = new NetworkConfigClient(backend).deleteSession(backend.network.getString("id"));
            if (mode.equals("lost") || mode.equals("wrong")) assertThrows(VmConfigClient.CommitUnknown.class, session::delete);
            else assertEquals(mode, assertThrows(VmConfigClient.RemoteFailure.class, session::delete).code);
            assertThrows(IOException.class, session::delete);
            assertEquals(1, backend.commits); assertEquals(0, backend.aborts); assertEquals(0, backend.recoveries);
        }
    }
    @Test public void appendFailureAbortsAndRecoveryIsExplicit() throws Exception {
        var backend = new Backend(); backend.mode = "append";
        var client = new NetworkConfigClient(backend);
        var session = client.deleteSession(backend.network.getString("id"));
        assertThrows(IOException.class, session::delete);
        assertEquals(1, backend.aborts); assertEquals(0, backend.commits);
        client.recoverConfiguration();
        assertEquals(1, backend.recoveries); assertEquals(0, backend.commits);
        assertThrows(IOException.class, session::delete);
    }
    @Test public void incompatibleCapabilityDoesNotFallBack() throws Exception {
        var backend = new Backend(); backend.mode = "unsupported";
        assertThrows(IOException.class, () -> new NetworkConfigClient(backend).deleteSession(backend.network.getString("id")));
        assertEquals(0, backend.releases); assertEquals(0, backend.commits);
    }
}
