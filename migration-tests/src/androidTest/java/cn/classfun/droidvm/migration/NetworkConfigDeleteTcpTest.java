// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.daemon.NetworkConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Requires the isolated --coordinated fixture, never the production daemon. */
public final class NetworkConfigDeleteTcpTest {
    @Test public void coordinatedDeletionThroughProductionClients() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        int port = Integer.parseInt(args.getString("port"));
        String token = args.getString("token");
        boolean uncertain = "uncertain".equals(args.getString("deletion_mode"));
        try (var connection = new VmConfigClientTest.Connection(port, token);
             var other = new VmConfigClientTest.Connection(port, token)) {
            int[] commits = {0}, appends = {0}, recoveries = {0};
            var networks = new NetworkConfigClient(request -> {
                switch (request.getString("command")) {
                    case "network_config_delete_commit": commits[0]++; break;
                    case "network_config_delete_append": appends[0]++; break;
                    case "configuration_recover": recoveries[0]++; break;
                }
                return connection.request(request);
            });
            var vms = new VmConfigClient(connection::request);
            var competingVms = new VmConfigClient(other::request);
            var competingNetworks = new NetworkConfigClient(other::request);
            assertTrue(networks.supported());
            for (int attempt = 0; attempt < (uncertain ? 1 : 2); attempt++) {
                String id = UUID.randomUUID().toString(), vmId = UUID.randomUUID().toString();
                var network = new JSONObject().put("id", id).put("name", "TCP " + id).put("schema", 2)
                    .put("bridge_name", "j" + id.substring(0, 8)).put("bridge_type", "gvisor").put("uplink_mode", "l3")
                    .put("l3", new JSONObject().put("vlans", new JSONArray()))
                    .put("extension", "保留 🌏".repeat(12000));
                networks.create(id).save(network);
                var vm = new JSONObject().put("id", vmId).put("name", "TCP VM").put("extension", 9007199254740993L)
                    .put("networks", new JSONArray().put(new JSONObject().put("network_id", id).put("extension", true)));
                vms.edit().saveVm(vm, true);

                // VM confirmation and network confirmation independently reject drift.
                var staleVm = networks.deleteSession(id);
                var edit = competingVms.edit();
                vm.put("name", "changed during confirmation");
                edit.saveVm(vm, false);
                assertEquals("CONFLICT", assertThrows(VmConfigClient.RemoteFailure.class, staleVm::delete).code);
                int submitted = commits[0];
                assertThrows(IOException.class, staleVm::delete);
                assertEquals(submitted, commits[0]);
                var staleNetwork = networks.deleteSession(id);
                var networkEdit = competingNetworks.edit(id);
                var changed = networkEdit.configuration().put("name", "edited " + id);
                networkEdit.save(changed);
                assertEquals("CONFLICT", assertThrows(VmConfigClient.RemoteFailure.class, staleNetwork::delete).code);
                submitted = commits[0];
                assertThrows(IOException.class, staleNetwork::delete);
                assertEquals(submitted, commits[0]);

                networks.start(id);
                var confirmed = networks.deleteSession(id);
                confirmed.configuration().put("name", "detached mutation");
                confirmed.vmDocument().put("vms", new JSONArray());
                assertEquals("edited " + id, confirmed.configuration().getString("name"));
                assertTrue(confirmed.vmDocument().getJSONArray("vms").length() > 0);
                int beforeCommit = commits[0], beforeAppend = appends[0];
                if (uncertain) {
                    assertEquals("COMMIT_UNCERTAIN", assertThrows(VmConfigClient.RemoteFailure.class, confirmed::delete).code);
                    assertEquals("UNAVAILABLE", assertThrows(VmConfigClient.RemoteFailure.class, vms::read).code);
                    assertEquals("UNAVAILABLE", assertThrows(VmConfigClient.RemoteFailure.class, networks::planningDocument).code);
                } else if (attempt == 1) {
                    connection.loseCommitReply = true;
                    assertThrows(VmConfigClient.CommitUnknown.class, confirmed::delete);
                } else {
                    var saved = confirmed.delete();
                    assertTrue(saved.persisted); assertFalse(saved.durabilityUncertain);
                }
                assertEquals(beforeCommit + 1, commits[0]);
                assertTrue(appends[0] - beforeAppend > 1);
                assertThrows(IOException.class, confirmed::delete);
                assertEquals(beforeCommit + 1, commits[0]);
                assertEquals(0, recoveries[0]);
                if (uncertain || attempt == 1) networks.recoverConfiguration();
                assertEquals(uncertain || attempt == 1 ? 1 : 0, recoveries[0]);
                assertEquals(beforeCommit + 1, commits[0]);

                try (var reopened = new VmConfigClientTest.Connection(port, token)) {
                    var rows = new NetworkConfigClient(reopened::request).planningDocument().getJSONArray("networks");
                    for (int i = 0; i < rows.length(); i++) assertNotEquals(id, rows.getJSONObject(i).getString("id"));
                    var vmRows = new VmConfigClient(reopened::request).read().document().getJSONArray("vms");
                    boolean found = false;
                    for (int i = 0; i < vmRows.length(); i++) {
                        var row = vmRows.getJSONObject(i);
                        if (vmId.equals(row.getString("id"))) {
                            found = true;
                            assertEquals(0, row.getJSONArray("networks").length());
                            assertEquals(9007199254740993L, row.getLong("extension"));
                            assertEquals("changed during confirmation", row.getString("name"));
                        }
                    }
                    assertTrue(found);
                }
            }
        }
    }
}
