// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;

public final class VmConfigImportTest {
    private static JSONObject row(String id, String name) throws JSONException {
        return new JSONObject().put("id", id).put("name", name);
    }
    private static JSONObject version(String revision) throws JSONException {
        return new JSONObject().put("success", true).put("revision", revision)
            .put("persisted", true).put("recovered", false).put("durability_uncertain", false);
    }
    private static final class Backend implements VmConfigClient.Rpc {
        JSONObject document;
        ByteArrayOutputStream upload = new ByteArrayOutputStream();
        String failure;
        boolean lost;
        int commits, begins;
        Backend() throws JSONException {
            document = new JSONObject().put("future_root", "retained").put("vms",
                new JSONArray().put(row("first", "original")).put(row("untouched", "keep")));
        }
        public JSONObject request(JSONObject request) throws IOException, JSONException {
            String command = request.getString("command");
            if ("vm_config_snapshot".equals(command)) {
                byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
                var metadata = version("opening").put("type", "metadata").put("encoding", "base64-json").put("bytes", bytes.length);
                return new JSONObject().put("success", true).put("snapshot_id", "view").put("epoch", "epoch")
                    .put("cursor", "1").put("total", 2).put("next_offset", JSONObject.NULL)
                    .put("items", new JSONArray().put(metadata).put(new JSONObject().put("type", "chunk")
                        .put("offset", 0).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))));
            }
            if ("resource_snapshot_release".equals(command)) return new JSONObject().put("success", true);
            if ("vm_config_upload_begin".equals(command)) {
                assertEquals("opening", request.getString("revision"));
                begins++; upload.reset();
                return new JSONObject().put("success", true).put("upload_id", "upload").put("chunk_bytes", 48 << 10);
            }
            if ("vm_config_upload_append".equals(command)) {
                assertEquals(upload.size(), request.getInt("offset"));
                upload.write(Base64.decode(request.getString("data"), Base64.NO_WRAP));
                return new JSONObject().put("success", true).put("received", upload.size());
            }
            if ("vm_config_upload_commit".equals(command)) {
                commits++;
                if (failure != null) return new JSONObject().put("success", false)
                    .put("error", new JSONObject().put("code", failure));
                document = new JSONObject(upload.toString(StandardCharsets.UTF_8.name()));
                if (lost) throw new IOException("successful commit response lost");
                return version("saved");
            }
            throw new IOException("Unexpected command: " + command);
        }
    }

    @Test public void mixedImportKeepsUnrelatedRowsRootFieldsAndListOrder() throws Exception {
        var backend = new Backend();
        var session = new VmConfigClient(backend).edit();
        var imported = new JSONArray().put(row("new", "appended")).put(row("first", "replacement"));
        session.mergeVms(imported);
        imported.getJSONObject(1).put("name", "caller mutation");
        var rows = backend.document.getJSONArray("vms");
        assertEquals(3, rows.length());
        assertEquals("replacement", rows.getJSONObject(0).getString("name"));
        assertEquals("keep", rows.getJSONObject(1).getString("name"));
        assertEquals("new", rows.getJSONObject(2).getString("id"));
        assertEquals("retained", backend.document.getString("future_root"));
        assertEquals(1, backend.commits);
        assertEquals("replacement", session.document().getJSONArray("vms").getJSONObject(0).getString("name"));
    }

    @Test public void duplicateImportIdsFailBeforeUploadWithoutMutatingBaseline() throws Exception {
        var backend = new Backend(); var session = new VmConfigClient(backend).edit();
        assertThrows(IOException.class, () -> session.mergeVms(new JSONArray().put(row("new", "a")).put(row("new", "b"))));
        assertEquals(0, backend.begins);
        assertEquals(2, session.document().getJSONArray("vms").length());
    }

    @Test public void conflictOrLostCommitCannotSilentlyRebaseImport() throws Exception {
        for (boolean lost : new boolean[]{false, true}) {
            var backend = new Backend(); backend.lost = lost; backend.failure = lost ? null : "CONFLICT";
            var session = new VmConfigClient(backend).edit();
            var imported = new JSONArray().put(row("first", "changed")).put(row("new", "created"));
            assertThrows(IOException.class, () -> session.mergeVms(imported));
            assertTrue(session.requiresReload());
            assertThrows(IOException.class, () -> session.mergeVms(imported));
            assertEquals(1, backend.commits);
            assertEquals("original", session.document().getJSONArray("vms").getJSONObject(0).getString("name"));
            assertEquals(lost ? 3 : 2, backend.document.getJSONArray("vms").length());
        }
    }
}
