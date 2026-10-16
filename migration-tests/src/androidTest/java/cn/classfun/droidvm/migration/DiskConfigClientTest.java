// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.util.Base64;
import cn.classfun.droidvm.lib.daemon.DiskConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;

public final class DiskConfigClientTest {
    private static final class Backend implements VmConfigClient.Rpc {
        final JSONObject document = new JSONObject().put("disks", new JSONArray()).put("root", 9007199254740993L);
        int releases;
        String revision = "observed";
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
                case "capabilities": return new JSONObject().put("success", true).put("disk_config_persistence", true)
                    .put("disk_config_snapshot", "base64-json-chunks-v1").put("disk_config_events", "invalidation-v1");
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
}
