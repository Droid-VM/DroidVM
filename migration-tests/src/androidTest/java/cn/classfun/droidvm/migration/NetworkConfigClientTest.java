// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import cn.classfun.droidvm.lib.daemon.NetworkConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;

public final class NetworkConfigClientTest {
    private static final class Backend implements VmConfigClient.Rpc {
        final String id = UUID.randomUUID().toString();
        JSONObject config, submitted;
        String failure;
        boolean loseReply, wrongId;
        int writes;
        Backend() throws Exception { config = new JSONObject().put("id", id).put("name", "original").put("schema", 2); }
        public JSONObject request(JSONObject request) throws IOException, org.json.JSONException {
            String command = request.getString("command");
            if ("capabilities".equals(command))
                return new JSONObject().put("success", true).put("network_config_update", "compare-config-v1");
            if ("network_config_get".equals(command))
                return new JSONObject().put("success", true).put("config", config);
            writes++;
            submitted = new JSONObject(request.toString());
            if (loseReply) throw new IOException("lost acknowledgement");
            if (failure != null) return new JSONObject().put("success", false)
                .put("error", new JSONObject().put("code", failure));
            return new JSONObject().put("success", true).put("network_id", wrongId ? UUID.randomUUID().toString() : id);
        }
    }

    @Test public void capabilityFailureAndPartialNativeApiNeverEnableLocalWrites() throws Exception {
        assertFalse(new NetworkConfigClient(r -> new JSONObject().put("success", true)).supported());
        for (String key : new String[]{"network_config_persistence", "network_config_events", "network_config_update", "network_config_import"}) {
            var client = new NetworkConfigClient(r -> new JSONObject().put("success", true).put(key, true));
            assertThrows(IOException.class, client::supported);
        }
        assertThrows(IOException.class, () -> new NetworkConfigClient(r -> { throw new IOException("offline"); }).supported());
        assertThrows(IOException.class, () -> new NetworkConfigClient(r -> new JSONObject().put("success", "true")).supported());
    }

    @Test public void baselineIsDetachedAndSuccessfulSessionCannotSubmitTwice() throws Exception {
        var backend = new Backend();
        var client = new NetworkConfigClient(backend);
        assertTrue(client.supported());
        var session = client.edit(backend.id);
        var edited = session.configuration().put("name", "edited");
        backend.config.put("name", "concurrent");
        session.save(edited);
        assertEquals("original", backend.submitted.getJSONObject("expected").getString("name"));
        assertEquals("edited", backend.submitted.getJSONObject("config").getString("name"));
        assertTrue(session.requiresReload());
        assertThrows(IOException.class, () -> session.save(edited));
        assertEquals(1, backend.writes);
    }

    @Test public void conflictsAndUncertainResultsRequireReloadWithoutRetry() throws Exception {
        for (String code : new String[]{"CONFLICT", "NOT_FOUND", "COMMIT_UNCERTAIN", "INTERNAL", "UNKNOWN"}) {
            var backend = new Backend(); backend.failure = code;
            var session = new NetworkConfigClient(backend).edit(backend.id);
            assertThrows(IOException.class, () -> session.save(session.configuration()));
            assertTrue(session.requiresReload());
            assertThrows(IOException.class, () -> session.save(session.configuration()));
            assertEquals(1, backend.writes);
        }
    }

    @Test public void lostOrMismatchedAcknowledgementBlocksCreateResubmission() throws Exception {
        for (boolean lost : new boolean[]{true, false}) {
            var backend = new Backend(); backend.loseReply = lost; backend.wrongId = !lost;
            var session = new NetworkConfigClient(backend).create(backend.id);
            assertNull(session.configuration());
            assertThrows(VmConfigClient.CommitUnknown.class, () -> session.save(backend.config));
            assertFalse(backend.submitted.has("expected"));
            assertEquals("network_create", backend.submitted.getString("command"));
            assertThrows(IOException.class, () -> session.save(backend.config));
            assertEquals(1, backend.writes);
        }
    }

    @Test public void confirmedPreInstallFailureKeepsOriginalExpectation() throws Exception {
        var backend = new Backend(); backend.failure = "IO_ERROR";
        var session = new NetworkConfigClient(backend).edit(backend.id);
        assertThrows(VmConfigClient.RemoteFailure.class, () -> session.save(session.configuration()));
        assertFalse(session.requiresReload());
        backend.failure = null;
        session.save(session.configuration().put("name", "retry"));
        assertEquals("original", backend.submitted.getJSONObject("expected").getString("name"));
        assertEquals(2, backend.writes);
    }

    @Test public void startOnlySendsIdentityAndNeverSavesOrRetries() throws Exception {
        String id = UUID.randomUUID().toString();
        int[] calls = {0};
        var client = new NetworkConfigClient(request -> {
            calls[0]++;
            assertEquals("network_start", request.getString("command"));
            assertEquals(id, request.getString("network_id"));
            assertFalse(request.has("config"));
            assertFalse(request.has("expected"));
            return new JSONObject().put("success", true).put("network_id", id).put("state", "running");
        });
        client.start(id);
        assertEquals(1, calls[0]);
        calls[0] = 0;
        var disconnected = new NetworkConfigClient(request -> { calls[0]++; throw new IOException("lost response"); });
        assertThrows(NetworkConfigClient.StartUnknown.class, () -> disconnected.start(id));
        assertEquals(1, calls[0]);
    }

    @Test public void startDistinguishesRejectionFromUnknownResponse() throws Exception {
        String id = UUID.randomUUID().toString();
        var rejected = new NetworkConfigClient(request -> new JSONObject().put("success", false)
            .put("error", new JSONObject().put("code", "CONFLICT")));
        assertThrows(VmConfigClient.RemoteFailure.class, () -> rejected.start(id));
        for (String state : new String[]{"stopped", "starting", "unknown"}) {
            var invalid = new NetworkConfigClient(request -> new JSONObject().put("success", true)
                .put("network_id", id).put("state", state));
            assertThrows(NetworkConfigClient.StartUnknown.class, () -> invalid.start(id));
        }
        var wrongId = new NetworkConfigClient(request -> new JSONObject().put("success", true)
            .put("network_id", UUID.randomUUID().toString()).put("state", "running"));
        assertThrows(NetworkConfigClient.StartUnknown.class, () -> wrongId.start(id));
    }
}
