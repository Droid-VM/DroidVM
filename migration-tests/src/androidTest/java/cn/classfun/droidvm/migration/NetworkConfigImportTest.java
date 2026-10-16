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

public final class NetworkConfigImportTest {
    private static JSONObject row(String name) throws JSONException {
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("name", name).put("schema", 2);
    }
    private static final class Backend implements VmConfigClient.Rpc {
        final JSONObject first = row("first"), second = row("second");
        final ByteArrayOutputStream upload = new ByteArrayOutputStream();
        JSONObject submitted;
        int releases, pages, appends, commits, begins, aborts;
        boolean badPage, failAppend, lost, wrongIds, reject;
        Backend() throws JSONException {}
        public JSONObject request(JSONObject request) throws IOException, JSONException {
            String command = request.getString("command");
            if ("capabilities".equals(command)) return new JSONObject().put("success", true)
                .put("network_config_import", "conditional-chunks-v1").put("network_config_events", "invalidation-v1");
            if ("network_config_snapshot".equals(command) || "resource_snapshot_page".equals(command)) {
                boolean next = "resource_snapshot_page".equals(command);
                pages++;
                if (next) {
                    assertEquals("view", request.getString("snapshot_id"));
                    assertEquals(1, request.getInt("offset"));
                }
                return new JSONObject().put("success", true).put("snapshot_id", "view")
                    .put("epoch", next && badPage ? "changed" : "epoch").put("cursor", "7").put("total", 2)
                    .put("next_offset", next ? JSONObject.NULL : 1)
                    .put("items", new JSONArray().put(next ? second : first));
            }
            if ("resource_snapshot_release".equals(command)) { releases++; return new JSONObject().put("success", true); }
            if ("network_config_import_begin".equals(command)) {
                begins++; upload.reset();
                return new JSONObject().put("success", true).put("upload_id", "import").put("chunk_bytes", 48 << 10);
            }
            if ("network_config_import_append".equals(command)) {
                appends++;
                if (failAppend) throw new IOException("append disconnected");
                byte[] bytes = Base64.decode(request.getString("data"), Base64.NO_WRAP);
                assertTrue(bytes.length <= (48 << 10));
                assertEquals(upload.size(), request.getInt("offset"));
                upload.write(bytes);
                return new JSONObject().put("success", true).put("received", upload.size());
            }
            if ("network_config_import_abort".equals(command)) { aborts++; return new JSONObject().put("success", true); }
            if ("network_config_import_commit".equals(command)) {
                commits++;
                submitted = new JSONObject(upload.toString(StandardCharsets.UTF_8.name()));
                if (lost) throw new IOException("commit response lost");
                if (reject) return new JSONObject().put("success", false).put("error", new JSONObject().put("code", "CONFLICT"));
                var ids = new JSONArray();
                var operations = submitted.getJSONArray("operations");
                for (int i = 0; i < operations.length(); i++)
                    ids.put(wrongIds ? UUID.randomUUID().toString() : operations.getJSONObject(i).getJSONObject("config").getString("id"));
                return new JSONObject().put("success", true).put("network_ids", ids);
            }
            throw new IOException("Unexpected command: " + command);
        }
    }

    @Test public void fixedPagedBaselineBuildsOneChunkedMixedImport() throws Exception {
        var backend = new Backend();
        var session = new NetworkConfigClient(backend).importSession();
        var edited = session.document().getJSONArray("networks").getJSONObject(0).put("name", "edited");
        edited.put("payload", "x".repeat(60000));
        backend.first.put("name", "later backend mutation");
        var added = row("new");
        session.merge(new JSONArray().put(edited).put(added));
        var operations = backend.submitted.getJSONArray("operations");
        assertEquals("first", operations.getJSONObject(0).getJSONObject("expected").getString("name"));
        assertEquals("edited", operations.getJSONObject(0).getJSONObject("config").getString("name"));
        assertTrue(operations.getJSONObject(1).isNull("expected"));
        assertEquals(2, backend.pages); assertEquals(1, backend.releases);
        assertEquals(2, backend.appends); assertEquals(1, backend.commits); assertEquals(0, backend.aborts);
        assertThrows(IOException.class, () -> session.merge(new JSONArray().put(added)));
        assertEquals(1, backend.commits);
    }

    @Test public void changedPageIsRejectedAndSnapshotReleased() throws Exception {
        var backend = new Backend(); backend.badPage = true;
        assertThrows(IOException.class, () -> new NetworkConfigClient(backend).importSession());
        assertEquals(1, backend.releases); assertEquals(0, backend.begins);
    }

    @Test public void duplicateIdsRejectBeforeUploadAndAppendFailureAborts() throws Exception {
        var backend = new Backend();
        var session = new NetworkConfigClient(backend).importSession();
        var added = row("new");
        assertThrows(IOException.class, () -> session.merge(new JSONArray().put(added).put(added)));
        assertEquals(0, backend.begins);
        backend.failAppend = true;
        assertThrows(IOException.class, () -> session.merge(new JSONArray().put(added)));
        assertEquals(1, backend.aborts); assertEquals(0, backend.commits);
    }

    @Test public void unknownOrConflictingCommitNeverAbortsOrResubmits() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var backend = new Backend(); backend.lost = mode == 0; backend.wrongIds = mode == 1; backend.reject = mode == 2;
            var session = new NetworkConfigClient(backend).importSession();
            var imported = new JSONArray().put(row("new"));
            if (mode < 2) assertThrows(VmConfigClient.CommitUnknown.class, () -> session.merge(imported));
            else assertThrows(VmConfigClient.RemoteFailure.class, () -> session.merge(imported));
            assertThrows(IOException.class, () -> session.merge(imported));
            assertEquals(1, backend.commits); assertEquals(0, backend.aborts);
        }
    }
}
