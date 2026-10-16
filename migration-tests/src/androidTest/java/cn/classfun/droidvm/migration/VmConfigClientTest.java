// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.daemon.Protocol;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class VmConfigClientTest {
    private static final class Connection implements AutoCloseable {
        final Socket socket;
        final Protocol protocol;
        final List<JSONObject> events = new ArrayList<>();
        boolean loseCommitReply;
        Connection(int port, String token) throws Exception {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(10000);
            protocol = new Protocol(socket.getInputStream(), socket.getOutputStream());
            assertTrue(request(new JSONObject().put("command", "auth").put("token", token)).getBoolean("success"));
        }
        JSONObject request(JSONObject request) throws IOException, org.json.JSONException {
            String id = UUID.randomUUID().toString();
            protocol.sendPacket(request.put("type", "request").put("request_id", id));
            for (;;) {
                JSONObject response = protocol.recvPacket();
                if (response == null) throw new IOException("Connection closed");
                if ("event".equals(response.optString("type"))) { events.add(response); continue; }
                assertEquals(id, response.getString("request_id"));
                if (loseCommitReply && "vm_config_upload_commit".equals(request.getString("command"))) {
                    loseCommitReply = false;
                    throw new IOException("Simulated lost successful commit reply");
                }
                return response;
            }
        }
        @Override public void close() throws IOException { socket.close(); }
    }
    @Test public void realTcpConfigurationLifecycle() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        int port = Integer.parseInt(args.getString("port")); String token = args.getString("token");
        try (var connection = new Connection(port, token); var second = new Connection(port, token)) {
            var client = new VmConfigClient(connection::request);
            var competing = new VmConfigClient(second::request);
            assertTrue(client.supported());
            var initial = client.read();
            assertEquals(0, initial.document().getJSONArray("vms").length());
            JSONObject document = initial.document();
            document.put("future_root", new JSONObject().put("text", "保留 🌏").put("integer", 9007199254740993L));
            String id = UUID.randomUUID().toString();
            document.getJSONArray("vms").put(new JSONObject().put("id", id).put("name", "Java 配置").put("future_vm", JSONObject.NULL));
            var saved = client.replace(initial.revision, document);
            assertTrue(saved.persisted); assertFalse(saved.durabilityUncertain);
            var base = client.read();
            assertEquals(9007199254740993L, base.document().getJSONObject("future_root").getLong("integer"));
            var privateCopy = base.document(); privateCopy.put("future_root", false);
            assertTrue(base.document().get("future_root") instanceof JSONObject);
            var edited = base.document().getJSONArray("vms").getJSONObject(0); edited.put("name", "winner");
            competing.apply(base.revision, new JSONArray().put(new JSONObject().put("op", "replace").put("config", edited)));
            try { client.replace(base.revision, base.document()); fail("Stale editor must conflict"); }
            catch (VmConfigClient.RemoteFailure failure) { assertEquals("CONFLICT", failure.code); }
            assertEquals("Java 配置", base.document().getJSONArray("vms").getJSONObject(0).getString("name"));
            assertEquals("winner", client.read().document().getJSONArray("vms").getJSONObject(0).getString("name"));

            // One VM larger than a whole TCP frame, with root extensions retained.
            var largeBase = client.read();
            var large = largeBase.document();
            String payload = "x".repeat((8 << 20) + 123);
            large.getJSONArray("vms").getJSONObject(0).put("large", payload);
            var largeResult = client.replace(largeBase.revision, large);
            var fetched = client.read();
            assertEquals(largeResult.revision, fetched.revision);
            assertEquals(payload, fetched.document().getJSONArray("vms").getJSONObject(0).getString("large"));
            assertEquals("保留 🌏", fetched.document().getJSONObject("future_root").getString("text"));

            connection.request(new JSONObject().put("command", "resource_subscribe").put("epoch", fetched.epoch).put("cursor", fetched.cursor));
            var small = fetched.document(); small.getJSONArray("vms").getJSONObject(0).remove("large");
            small.put("lost_reply", true);
            connection.loseCommitReply = true;
            try { client.replace(fetched.revision, small); fail("Lost reply must be uncertain"); }
            catch (VmConfigClient.CommitUnknown expected) { assertNotNull(expected.getCause()); }
            var recovered = client.read();
            assertTrue(recovered.document().getBoolean("lost_reply"));
            assertNotEquals(fetched.revision, recovered.revision);
            // A ping flushes pending server event delivery before inspecting it.
            connection.request(new JSONObject().put("command", "ping").put("request", "PING"));
            assertTrue(connection.events.stream().anyMatch(event -> event.toString().contains("vm.config.invalidated")));
            connection.request(new JSONObject().put("command", "resource_unsubscribe"));
            assertEquals("STOPPED", connection.request(new JSONObject().put("command", "vm_list")).getJSONArray("data").getJSONObject(0).getString("state"));

            // The actual editor session retains its opening revision and preserves unrelated
            // entries/root extensions, including when USB failure keeps an editor open to retry.
            var session = client.edit();
            var detached = session.document(); detached.remove("future_root");
            assertTrue(session.document().has("future_root"));
            String original = session.document().getJSONArray("vms").getJSONObject(0).toString();
            var created = new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "editor created")
                .put("extension", new JSONObject().put("large_integer", 9007199254740993L));
            session.saveVm(created, true);
            created.put("name", "editor retry after USB failure");
            session.saveVm(created, false);
            var afterEditor = client.read().document();
            assertEquals(2, afterEditor.getJSONArray("vms").length());
            assertEquals(original, afterEditor.getJSONArray("vms").getJSONObject(0).toString());
            assertEquals("editor retry after USB failure", afterEditor.getJSONArray("vms").getJSONObject(1).getString("name"));
            assertEquals(9007199254740993L, afterEditor.getJSONObject("future_root").getLong("integer"));
            var otherBase = competing.read();
            var other = otherBase.document(); other.put("concurrent_root", true);
            competing.replace(otherBase.revision, other);
            created.put("name", "stale editor must not overwrite");
            try { session.saveVm(created, false); fail("Opening revision must conflict"); }
            catch (VmConfigClient.RemoteFailure failure) { assertEquals("CONFLICT", failure.code); }
            assertTrue(session.requiresReload());
            try { session.saveVm(created, false); fail("Cannot silently rebase stale edits"); }
            catch (IOException expected) { /* retained edits require an explicit new session */ }
            assertEquals("editor retry after USB failure", client.read().document().getJSONArray("vms").getJSONObject(1).getString("name"));

            var uncertain = client.edit();
            connection.loseCommitReply = true;
            created.put("name", "saved with lost response");
            try { uncertain.saveVm(created, false); fail("Missing commit response must block retries"); }
            catch (VmConfigClient.CommitUnknown expected) { assertTrue(uncertain.requiresReload()); }
            String committedRevision = client.status().revision;
            try { uncertain.saveVm(created, true); fail("Must not recreate after unknown save"); }
            catch (IOException expected) { assertEquals(committedRevision, client.status().revision); }
            assertEquals("saved with lost response", client.read().document().getJSONArray("vms").getJSONObject(1).getString("name"));
        }
        // Fresh Java socket reconstructs the same persistent state.
        try (var reopened = new Connection(port, token)) {
            assertTrue(new VmConfigClient(reopened::request).read().document().getBoolean("lost_reply"));
        }
    }

    @Test public void capabilityFailuresNeverAuthorizeLocalWrites() throws Exception {
        assertFalse(new VmConfigClient(request -> new JSONObject().put("success", true)).supported());
        for (String key : new String[]{"vm_config_updates", "vm_config_snapshot", "vm_config_upload", "vm_config_future"}) {
            try {
                new VmConfigClient(request -> new JSONObject().put("success", true).put(key, "unknown-version")).supported();
                fail("Partial or unknown native ownership must not select local writes");
            } catch (IOException expected) { /* unsupported is not legacy */ }
        }
        try {
            new VmConfigClient(request -> { throw new IOException("Disconnected"); }).supported();
            fail("Disconnect must not select local writes");
        } catch (IOException expected) { /* keep the failure */ }
        try {
            new VmConfigClient(request -> new JSONObject().put("success", false)).supported();
            fail("Rejected capability request must not select local writes");
        } catch (VmConfigClient.RemoteFailure expected) { /* keep the failure */ }
    }

    @Test public void malformedPageIsRejectedAndReleased() throws Exception {
        final boolean[] released = {false};
        var client = new VmConfigClient(request -> {
            if ("resource_snapshot_release".equals(request.getString("command"))) {
                released[0] = true; return new JSONObject().put("success", true);
            }
            return new JSONObject().put("success", true).put("snapshot_id", "view").put("epoch", "epoch").put("cursor", "1")
                .put("total", 2).put("next_offset", JSONObject.NULL).put("items", new JSONArray()
                    .put(new JSONObject().put("type", "metadata").put("encoding", "base64-json").put("bytes", 10))
                    .put(new JSONObject().put("type", "chunk").put("offset", 1).put("data", "eyJ2bXMiOltdfQ==")));
        });
        try { client.read(); fail("Invalid byte offset must fail"); }
        catch (IOException expected) { assertTrue(released[0]); }
    }
}
