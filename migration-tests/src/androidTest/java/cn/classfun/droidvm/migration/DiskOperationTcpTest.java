// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.lib.daemon.DiskConfigClient;
import cn.classfun.droidvm.lib.daemon.DiskOperationClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Only the isolated --vm-disks fixture; images stay in its "images" directory argument. */
public final class DiskOperationTcpTest {
    private static String rowId(VmConfigClientTest.Connection connection, String name) throws Exception {
        var rows = new DiskConfigClient(connection::request).read().document().getJSONArray("disks");
        for (int i = 0; i < rows.length(); i++)
            if (name.equals(rows.getJSONObject(i).optString("name"))) return rows.getJSONObject(i).getString("id");
        return null;
    }

    @Test public void productionDiskOperationClientOverTcp() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        int port = Integer.parseInt(args.getString("port"));
        String token = args.getString("token"), images = args.getString("images");
        try (var connection = new VmConfigClientTest.Connection(port, token)) {
            var client = new DiskOperationClient(connection::request);
            assertTrue(client.actions().containsAll(List.of("resize", "convert", "flatten", "create", "clone", "commit", "delete")));
            var created = client.await(client.start(new JSONObject().put("action", "create").put("folder", images)
                .put("name", "java.raw").put("format", "raw").put("size", 1048576)));
            assertTrue(created.succeeded());
            String id = created.result.getString("disk_id");
            assertEquals(id, rowId(connection, "java.raw"));
            var resize = new JSONObject().put("action", "resize").put("disk_id", id).put("size", 2097152);
            assertEquals("CONFLICT", assertThrows(VmConfigClient.RemoteFailure.class, () -> client.start(resize, "stale")).code);
            assertTrue(client.await(client.start(resize)).succeeded());
            // A lost reply is reported as unknown and never resubmitted; the clone still lands.
            connection.loseCommitReply = true;
            var clone = new JSONObject().put("action", "clone").put("disk_id", id).put("folder", images).put("name", "java-copy.raw");
            assertThrows(DiskOperationClient.SubmitUnknown.class, () -> client.start(clone));
            String copy = null;
            for (int i = 0; i < 200 && copy == null; i++) {
                // Views fail fast while the clone's registry transaction holds the gate.
                try { copy = rowId(connection, "java-copy.raw"); }
                catch (VmConfigClient.RemoteFailure failure) { if (!"UNAVAILABLE".equals(failure.code)) throw failure; }
                if (copy == null) Thread.sleep(50);
            }
            assertNotNull(copy);
            assertEquals("CONFLICT", assertThrows(VmConfigClient.RemoteFailure.class, () -> client.start(clone)).code);
            for (var target : List.of(copy, id)) {
                var delete = new JSONObject().put("action", "delete").put("disk_id", target).put("delete_files", true);
                DiskOperationClient.Task task = null;
                // The unseen clone task may still hold its reservation for a moment.
                for (int i = 0; task == null; i++) {
                    try { task = client.start(delete); }
                    catch (VmConfigClient.RemoteFailure failure) {
                        if (!"CONFLICT".equals(failure.code) || i >= 100) throw failure;
                        Thread.sleep(50);
                    }
                }
                assertTrue(client.await(task).succeeded());
            }
            assertNull(rowId(connection, "java.raw"));
            assertNull(rowId(connection, "java-copy.raw"));
            assertEquals(0, client.recover());
        }
    }
}
