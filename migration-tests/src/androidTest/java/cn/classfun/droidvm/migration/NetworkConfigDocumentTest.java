// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import cn.classfun.droidvm.lib.daemon.NetworkConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;

public final class NetworkConfigDocumentTest {
    private static final class Backend implements VmConfigClient.Rpc {
        final byte[] bytes;
        int releases, pages;
        String fault = "";
        Backend(byte[] bytes) { this.bytes = bytes; }
        public JSONObject request(JSONObject request) throws IOException, JSONException {
            String command = request.getString("command");
            if ("capabilities".equals(command)) return new JSONObject().put("success", true)
                .put("network_config_document", "encoding".equals(fault) ? "future" : "base64-json-chunks-v1");
            if ("resource_snapshot_release".equals(command)) {
                releases++; return new JSONObject().put("success", true);
            }
            if (!"network_config_document".equals(command) && !"resource_snapshot_page".equals(command))
                throw new IOException("Unexpected command: " + command);
            pages++;
            int index = request.optInt("offset", 0), total = 1 + (bytes.length + 49151) / 49152;
            JSONObject row;
            if (index == 0) row = new JSONObject().put("type", "metadata").put("encoding", "base64-json")
                .put("bytes", "size".equals(fault) ? (8 << 20) + 1 : bytes.length);
            else {
                int offset = (index - 1) * 49152;
                String data = Base64.encodeToString(bytes, offset, Math.min(49152, bytes.length - offset), Base64.NO_WRAP);
                row = new JSONObject().put("type", "chunk").put("offset", "offset".equals(fault) ? offset + 1 : offset)
                    .put("data", "base64".equals(fault) ? data + "\n" : data);
            }
            var page = new JSONObject().put("success", true).put("snapshot_id", "fixed")
                .put("epoch", index > 0 && "epoch".equals(fault) ? "changed" : "epoch").put("cursor", "2")
                .put("total", total).put("items", new JSONArray().put(row));
            if (!"continuation".equals(fault)) page.put("next_offset", index + 1 < total ? index + 1 : JSONObject.NULL);
            return page;
        }
    }
    private static JSONObject row() throws JSONException {
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "网络🌏").put("extension", "");
    }
    @Test public void exactEightMiBSingleRowReadsAndReleases() throws Exception {
        var row = row();
        var document = new JSONObject().put("networks", new JSONArray().put(row));
        row.put("extension", "x".repeat((8 << 20) - document.toString().getBytes(StandardCharsets.UTF_8).length));
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(8 << 20, bytes.length);
        var backend = new Backend(bytes);
        var actual = new NetworkConfigClient(backend).planningDocument();
        assertEquals(row.toString(), actual.getJSONArray("networks").getJSONObject(0).toString());
        assertTrue(backend.pages > 100); assertEquals(1, backend.releases);
    }
    @Test public void malformedPagesFailClosedAndRelease() throws Exception {
        byte[] bytes = new JSONObject().put("networks", new JSONArray().put(row())).toString().getBytes(StandardCharsets.UTF_8);
        for (String fault : new String[]{"size", "offset", "base64", "epoch", "continuation"}) {
            var backend = new Backend(bytes); backend.fault = fault;
            assertThrows(IOException.class, () -> new NetworkConfigClient(backend).planningDocument());
            assertEquals(1, backend.releases);
        }
        var backend = new Backend(bytes); backend.fault = "encoding";
        assertThrows(IOException.class, () -> new NetworkConfigClient(backend).planningDocument());
        assertEquals(0, backend.pages);
    }
    @Test public void invalidUtf8AndDuplicateIdentityReject() throws Exception {
        var invalid = new Backend(new byte[]{(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> new NetworkConfigClient(invalid).planningDocument());
        assertEquals(1, invalid.releases);
        var row = row();
        var duplicate = new Backend(new JSONObject().put("networks", new JSONArray().put(row).put(row))
            .toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> new NetworkConfigClient(duplicate).planningDocument());
        assertEquals(1, duplicate.releases);
    }
}
