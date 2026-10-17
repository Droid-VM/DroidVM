// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.daemon.ConfigurationClient;
import cn.classfun.droidvm.lib.daemon.DiskConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Only the isolated --vm-disks fixture; image files and VMMs are never created. */
public final class ConfigurationTcpTest {
    @Test public void jointProductionClientOverTcp() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        int port = Integer.parseInt(args.getString("port")); String token = args.getString("token");
        String vmRevision, diskRevision;
        try (var connection = new VmConfigClientTest.Connection(port, token);
             var other = new VmConfigClientTest.Connection(port, token)) {
            int[] commits = {0};
            var client = new ConfigurationClient(request -> {
                if ("configuration_vm_disks_commit".equals(request.getString("command"))) commits[0]++;
                return connection.request(request);
            });
            assertTrue(client.supported());
            var edit = client.edit(); var vms = edit.vms(); var disks = edit.disks();
            assertEquals(0, vms.getJSONArray("vms").length()); assertEquals(0, disks.getJSONArray("disks").length());
            var disk = new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "base.qcow2").put("folder", "/fixture");
            var vm = new JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Joint TCP VM")
                .put("disks", new JSONArray().put(new JSONObject().put("path", "/fixture/base.qcow2").put("readonly", false)));
            vms.getJSONArray("vms").put(vm); disks.getJSONArray("disks").put(disk);
            vms.put("future_integer", 9007199254740993L); disks.put("future_integer", 9007199254740993L);
            var saved = edit.save(vms, disks);
            assertTrue(saved.vms.persisted); assertTrue(saved.disks.persisted);
            assertFalse(saved.vms.durabilityUncertain); assertFalse(saved.disks.durabilityUncertain);
            assertThrows(IOException.class, () -> edit.save(vms, disks));
            var bad = client.edit(); var removed = bad.disks().put("disks", new JSONArray());
            assertEquals("CONFLICT", assertThrows(VmConfigClient.RemoteFailure.class, () -> bad.save(bad.vms(), removed)).code);
            assertEquals(saved.vms.revision, new VmConfigClient(connection::request).status().revision);
            assertEquals(saved.disks.revision, new DiskConfigClient(connection::request).status().revision);
            var stale = client.edit(); var winner = new ConfigurationClient(other::request).edit();
            winner.save(winner.vms().put("winner", true), winner.disks().put("winner", true));
            int submitted = commits[0];
            assertEquals("CONFLICT", assertThrows(VmConfigClient.RemoteFailure.class, () -> stale.save(stale.vms(), stale.disks())).code);
            assertEquals(submitted, commits[0]);
            var large = client.edit(); String payload = "x".repeat((1 << 20) + 123);
            large.save(large.vms().put("large", payload), large.disks().put("large", payload));
            var fixed = client.edit();
            assertEquals(payload, fixed.vms().getString("large")); assertEquals(payload, fixed.disks().getString("large"));
            fixed.vms().remove("large"); assertTrue(fixed.vms().has("large"));
            var view = new VmConfigClient(connection::request).read();
            connection.request(new JSONObject().put("command", "resource_subscribe").put("epoch", view.epoch).put("cursor", view.cursor));
            var smallVms = fixed.vms(); var smallDisks = fixed.disks();
            smallVms.remove("large"); smallDisks.remove("large");
            smallVms.getJSONArray("vms").getJSONObject(0).put("disks", new JSONArray());
            smallDisks.put("disks", new JSONArray());
            smallVms.put("joint_lost", true); smallDisks.put("joint_lost", true);
            connection.loseCommitReply = true;
            assertThrows(VmConfigClient.CommitUnknown.class, () -> fixed.save(smallVms, smallDisks));
            submitted = commits[0];
            assertThrows(IOException.class, () -> fixed.save(smallVms, smallDisks)); assertEquals(submitted, commits[0]);
            vmRevision = new VmConfigClient(connection::request).status().revision;
            diskRevision = new DiskConfigClient(connection::request).status().revision;
            var current = client.edit();
            assertTrue(current.vms().getBoolean("joint_lost")); assertTrue(current.disks().getBoolean("joint_lost"));
            assertEquals(0, current.disks().getJSONArray("disks").length());
            connection.request(new JSONObject().put("command", "ping").put("request", "PING"));
            assertTrue(connection.events.stream().anyMatch(e -> e.toString().contains("vm.config.invalidated")));
            assertTrue(connection.events.stream().anyMatch(e -> e.toString().contains("disk.config.invalidated")));
            connection.request(new JSONObject().put("command", "resource_unsubscribe"));
        }
        try (var fresh = new VmConfigClientTest.Connection(port, token)) {
            var vm = new VmConfigClient(fresh::request).read(); var disk = new DiskConfigClient(fresh::request).read();
            assertEquals(vmRevision, vm.revision); assertEquals(diskRevision, disk.revision);
            assertEquals(0, vm.document().getJSONArray("vms").getJSONObject(0).getJSONArray("disks").length());
            assertTrue(disk.document().getBoolean("joint_lost"));
        }
    }
}
