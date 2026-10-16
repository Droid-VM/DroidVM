// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.daemon.DiskConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

/** Requires --disk-config on the isolated fixture, never the production daemon. */
public final class DiskConfigTcpTest {
    @Test public void productionDiskClientLifecycleOverTcp() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        int port = Integer.parseInt(args.getString("port")); String token = args.getString("token");
        String committedRevision;
        try (var connection = new VmConfigClientTest.Connection(port, token);
             var other = new VmConfigClientTest.Connection(port, token)) {
            int[] commits = {0};
            var client = new DiskConfigClient(request -> {
                if ("disk_config_upload_commit".equals(request.getString("command"))) commits[0]++;
                return connection.request(request);
            });
            var competing = new DiskConfigClient(other::request);
            assertTrue(client.readSupported());
            assertEquals(0, client.read().document().getJSONArray("disks").length());
            var session = client.edit(); var document = session.document();
            document.put("future_root", new JSONObject().put("text", "保留 🌏").put("integer", 9007199254740993L));
            String parent = UUID.randomUUID().toString(), child = UUID.randomUUID().toString();
            document.getJSONArray("disks")
                .put(new JSONObject().put("id", parent).put("name", "same.qcow2").put("folder", "/fixture/a").put("future", JSONObject.NULL))
                .put(new JSONObject().put("id", child).put("name", "same.qcow2").put("folder", "/fixture/b").put("parent", parent));
            var saved = session.save(document);
            assertTrue(saved.persisted); assertFalse(saved.durabilityUncertain);
            assertThrows(IOException.class, () -> session.save(document));
            assertEquals(1, commits[0]);
            var view = client.read();
            assertEquals(saved.revision, view.revision);
            assertEquals(2, view.document().getJSONArray("disks").length());
            assertEquals("/fixture/a", view.document().getJSONArray("disks").getJSONObject(0).getString("folder"));
            assertTrue(view.document().getJSONArray("disks").getJSONObject(0).isNull("future"));
            assertEquals(parent, view.document().getJSONArray("disks").getJSONObject(1).getString("parent"));
            view.document().put("future_root", false);
            assertEquals(9007199254740993L, view.document().getJSONObject("future_root").getLong("integer"));

            var stale = client.edit(); var winner = competing.edit();
            var winnerDocument = winner.document().put("winner", true);
            winner.save(winnerDocument);
            assertEquals("CONFLICT", assertThrows(VmConfigClient.RemoteFailure.class, () -> stale.save(stale.document())).code);
            assertThrows(IOException.class, () -> stale.save(stale.document()));
            assertEquals(1, commits[0]); // begin rejected the stale revision before any commit.

            var largeSession = client.edit(); var large = largeSession.document();
            String payload = "x".repeat((7 << 20) + 123);
            large.getJSONArray("disks").getJSONObject(0).put("large", payload);
            largeSession.save(large);
            var fetched = client.read();
            assertEquals(payload, fetched.document().getJSONArray("disks").getJSONObject(0).getString("large"));
            assertEquals(parent, fetched.document().getJSONArray("disks").getJSONObject(1).getString("parent"));
            connection.request(new JSONObject().put("command", "resource_subscribe").put("epoch", fetched.epoch).put("cursor", fetched.cursor));
            var uncertain = client.edit(); var small = uncertain.document();
            small.getJSONArray("disks").getJSONObject(0).remove("large"); small.put("lost_reply", true);
            connection.loseCommitReply = true;
            assertThrows(VmConfigClient.CommitUnknown.class, () -> uncertain.save(small));
            int submitted = commits[0];
            committedRevision = client.status().revision;
            assertThrows(IOException.class, () -> uncertain.save(small));
            assertEquals(submitted, commits[0]);
            assertEquals(committedRevision, client.status().revision);
            assertTrue(client.read().document().getBoolean("lost_reply"));
            connection.request(new JSONObject().put("command", "ping").put("request", "PING"));
            assertTrue(connection.events.stream().anyMatch(event -> event.toString().contains("disk.config.invalidated")));
            connection.request(new JSONObject().put("command", "resource_unsubscribe"));
        }
        try (var reopened = new VmConfigClientTest.Connection(port, token)) {
            var view = new DiskConfigClient(reopened::request).read();
            assertEquals(committedRevision, view.revision);
            assertTrue(view.document().getBoolean("lost_reply"));
            assertEquals(2, view.document().getJSONArray("disks").length());
        }
    }
}
