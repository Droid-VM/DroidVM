// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.util.Base64;
import cn.classfun.droidvm.lib.daemon.DiskConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;

public final class DiskConfigClientTest {
    static final class Backend implements VmConfigClient.Rpc {
        final JSONObject document = new JSONObject().put("disks", new JSONArray()).put("root", 9007199254740993L);
        int releases;
        String revision = "observed";
        String mode = "";
        final ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        int commits, appends, aborts;
        Backend() throws JSONException {
            for (String folder : new String[]{"/a", "/b"}) document.getJSONArray("disks").put(new JSONObject()
                .put("id", UUID.randomUUID().toString()).put("name", "same.qcow2").put("folder", folder)
                .put("parent", "historical broken link").put("future", JSONObject.NULL));
        }
        JSONObject version() throws JSONException {
            return new JSONObject().put("revision", revision).put("persisted", true).put("recovered", false).put("durability_uncertain", true);
        }
        public JSONObject request(JSONObject request) throws IOException, JSONException {
            switch (request.getString("command")) {
                case "capabilities":
                    var caps = new JSONObject().put("success", true).put("disk_config_persistence", true)
                        .put("disk_config_snapshot", "base64-json-chunks-v1").put("disk_config_events", "invalidation-v1");
                    if (!"read-only".equals(mode)) caps.put("disk_config_upload", "revisioned-chunks-v1");
                    return caps;
                case "disk_config_upload_begin":
                    assertEquals("observed", request.getString("revision"));
                    return new JSONObject().put("success", true).put("upload_id", "disk").put("chunk_bytes", 49152);
                case "disk_config_upload_append":
                    appends++;
                    if ("append".equals(mode)) throw new IOException("append disconnected");
                    assertEquals(uploaded.size(), request.getInt("offset"));
                    uploaded.write(Base64.decode(request.getString("data"), Base64.NO_WRAP));
                    return new JSONObject().put("success", true).put("received", uploaded.size());
                case "disk_config_upload_abort": aborts++; return new JSONObject().put("success", true);
                case "disk_config_upload_commit":
                    commits++;
                    if ("lost".equals(mode)) throw new IOException("lost commit response");
                    if ("CONFLICT".equals(mode) || "COMMIT_UNCERTAIN".equals(mode) || "INTERNAL".equals(mode))
                        return new JSONObject().put("success", false).put("error", new JSONObject().put("code", mode));
                    return version().put("success", true).put("revision", "malformed".equals(mode) ? "" : "saved")
                        .put("durability_uncertain", false);
                case "disk_config_status": return version().put("success", true);
                case "resource_snapshot_release": releases++; return new JSONObject().put("success", true);
                case "disk_config_snapshot":
                    byte[] data = document.toString().getBytes(StandardCharsets.UTF_8);
                    return new JSONObject().put("success", true).put("snapshot_id", "fixed").put("epoch", "epoch").put("cursor", "2")
                        .put("total", 2).put("next_offset", JSONObject.NULL).put("items", new JSONArray()
                            .put(version().put("type", "metadata").put("encoding", "base64-json").put("bytes", data.length))
                            .put(new JSONObject().put("type", "chunk").put("offset", 0).put("data", Base64.encodeToString(data, Base64.NO_WRAP))));
                default: throw new IOException("Unexpected disk client command");
            }
        }
    }
    @Test public void fixedDiskDocumentPreservesRegistryAndVersion() throws Exception {
        var backend = new Backend(); var client = new DiskConfigClient(backend);
        assertTrue(client.readSupported());
        assertEquals("observed", client.status().revision);
        var view = client.read();
        assertTrue(view.persisted); assertTrue(view.durabilityUncertain); assertFalse(view.recovered);
        assertEquals("epoch", view.epoch); assertEquals("2", view.cursor);
        assertEquals(backend.document.toString(), view.document().toString());
        view.document().put("disks", new JSONArray()); backend.document.put("root", 0);
        assertEquals(2, view.document().getJSONArray("disks").length());
        assertEquals(9007199254740993L, view.document().getLong("root"));
        assertEquals(1, backend.releases);
    }
    @Test public void malformedIdentityAndVersionStillReleaseSnapshot() throws Exception {
        for (String mode : new String[]{"duplicate", "identity", "name", "version"}) {
            var backend = new Backend(); var rows = backend.document.getJSONArray("disks");
            switch (mode) {
                case "duplicate": rows.getJSONObject(1).put("id", rows.getJSONObject(0).getString("id")); break;
                case "identity": rows.getJSONObject(0).put("id", "bad"); break;
                case "name": rows.getJSONObject(0).put("name", 1); break;
                case "version": backend.revision = ""; break;
            }
            assertThrows(IOException.class, () -> new DiskConfigClient(backend).read());
            assertEquals(1, backend.releases);
        }
    }
    @Test public void capabilityFailureNeverAuthorizesFallback() throws Exception {
        assertFalse(new DiskConfigClient(request -> new JSONObject().put("success", true)).readSupported());
        for (String key : new String[]{"disk_config_persistence", "disk_config_snapshot", "disk_config_events", "disk_config_future"})
            assertThrows(IOException.class, () -> new DiskConfigClient(request -> new JSONObject().put("success", true).put(key, "future")).readSupported());
        assertThrows(IOException.class, () -> new DiskConfigClient(request -> { throw new IOException("disconnect"); }).readSupported());
        assertThrows(VmConfigClient.RemoteFailure.class, () -> new DiskConfigClient(request -> new JSONObject().put("success", false)).readSupported());
    }
    @Test public void editCapturesPrivateBaselineAndUploadsOnce() throws Exception {
        var backend = new Backend(); var client = new DiskConfigClient(backend);
        var session = client.edit();
        session.document().put("root", 0);
        backend.document.put("root", 1);
        var desired = session.document().put("extension", "x".repeat(60000));
        assertEquals("saved", session.save(desired).revision);
        var actual = new JSONObject(backend.uploaded.toString(StandardCharsets.UTF_8.name()));
        assertEquals(9007199254740993L, actual.getLong("root"));
        assertEquals(2, actual.getJSONArray("disks").length());
        assertEquals(desired.toString(), actual.toString());
        assertEquals(2, backend.appends); assertEquals(1, backend.commits); assertEquals(0, backend.aborts);
        assertThrows(IOException.class, () -> session.save(desired));
        assertEquals(1, backend.commits);
    }
    @Test public void unknownAndRejectedCommitsNeverRebaseRetryOrAbort() throws Exception {
        for (String mode : new String[]{"lost", "malformed", "CONFLICT", "COMMIT_UNCERTAIN", "INTERNAL"}) {
            var backend = new Backend(); backend.mode = mode;
            var session = new DiskConfigClient(backend).edit();
            if ("CONFLICT".equals(mode) || "COMMIT_UNCERTAIN".equals(mode))
                assertEquals(mode, assertThrows(VmConfigClient.RemoteFailure.class, () -> session.save(session.document())).code);
            else assertThrows(VmConfigClient.CommitUnknown.class, () -> session.save(session.document()));
            assertThrows(IOException.class, () -> session.save(session.document()));
            assertEquals(1, backend.commits); assertEquals(0, backend.aborts);
        }
    }
    @Test public void failedAppendAbortsAndReadOnlyServiceCannotEdit() throws Exception {
        var backend = new Backend(); backend.mode = "append";
        var session = new DiskConfigClient(backend).edit();
        assertThrows(IOException.class, () -> session.save(session.document()));
        assertThrows(IOException.class, () -> session.save(session.document()));
        assertEquals(1, backend.appends); assertEquals(1, backend.aborts); assertEquals(0, backend.commits);
        backend = new Backend(); backend.mode = "read-only";
        var client = new DiskConfigClient(backend);
        assertTrue(client.readSupported());
        assertThrows(IOException.class, client::edit);
        assertEquals(0, backend.releases); assertEquals(0, backend.commits);
    }
}
