// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import cn.classfun.droidvm.lib.daemon.ConfigurationClient;
import cn.classfun.droidvm.lib.daemon.DiskConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public final class ConfigurationClientTest {
    private static final class Backend implements VmConfigClient.Rpc {
        final DiskConfigClientTest.Backend vm = new DiskConfigClientTest.Backend();
        final DiskConfigClientTest.Backend disk = new DiskConfigClientTest.Backend();
        final DiskConfigClientTest.Backend upload = new DiskConfigClientTest.Backend();
        String mode = "";
        int releases, snapshots, begins;
        Backend() throws JSONException {
            vm.document.remove("disks"); vm.document.put("vms", new JSONArray());
            vm.revision = "vm-opening"; disk.revision = "disk-opening";
        }
        public JSONObject request(JSONObject request) throws IOException, JSONException {
            String command = request.getString("command");
            if (command.equals("capabilities")) return new JSONObject().put("success", true)
                .put("configuration_vm_disks", "revisioned-chunks-v1")
                .put("vm_config_snapshot", "base64-json-chunks-v1").put("disk_config_snapshot", "base64-json-chunks-v1");
            if (command.equals("resource_snapshot_release")) { releases++; return new JSONObject().put("success", true); }
            if (command.equals("vm_config_snapshot") || command.equals("disk_config_snapshot")) {
                snapshots++;
                var backend = command.equals("vm_config_snapshot") ? vm : disk;
                var result = backend.request(new JSONObject().put("command", "disk_config_snapshot"));
                if (mode.equals("restart") && backend == disk) result.put("epoch", "another-daemon");
                return result;
            }
            if (!command.startsWith("configuration_vm_disks_")) throw new IOException("Unexpected command: " + command);
            var mapped = new JSONObject(request.toString()).put("command", command.replace("configuration_vm_disks_", "disk_config_upload_"));
            if (command.endsWith("_begin")) {
                begins++;
                assertEquals("vm-opening", request.getString("vm_revision"));
                assertEquals("disk-opening", request.getString("disk_revision"));
                if (mode.equals("stale-begin")) return new JSONObject().put("success", false)
                    .put("error", new JSONObject().put("code", "CONFLICT"));
                mapped.put("revision", "observed");
            }
            upload.mode = mode;
            var result = upload.request(mapped);
            if (!command.endsWith("_commit") || !result.getBoolean("success")) return result;
            var pair = new JSONObject().put("success", true)
                .put("vms", new JSONObject(result.toString()).put("revision", "vm-saved"))
                .put("disks", new JSONObject(result.toString()));
            if (mode.equals("missing-vms")) pair.remove("vms");
            return pair;
        }
    }

    @Test public void privateSnapshotsAndBothRevisionsAreRetained() throws Exception {
        var backend = new Backend(); var client = new ConfigurationClient(backend);
        assertTrue(client.supported());
        var edit = client.edit();
        edit.vms().put("root", 0); edit.disks().put("disks", new JSONArray());
        backend.vm.document.put("root", 1); backend.disk.document.put("root", 1);
        var vms = edit.vms().put("extension", "x".repeat(60000));
        var disks = edit.disks();
        assertEquals(9007199254740993L, vms.getLong("root"));
        assertEquals(2, disks.getJSONArray("disks").length());
        var result = edit.save(vms, disks);
        assertEquals("vm-saved", result.vms.revision); assertEquals("saved", result.disks.revision);
        var saved = new JSONObject(backend.upload.uploaded.toString(StandardCharsets.UTF_8.name()));
        assertEquals(9007199254740993L, saved.getJSONObject("disks").getLong("root"));
        assertEquals("x".repeat(60000), saved.getJSONObject("vms").getString("extension"));
        assertEquals(2, backend.releases); assertEquals(2, backend.upload.appends);
        assertEquals(1, backend.upload.commits); assertEquals(0, backend.upload.aborts);
        assertThrows(IOException.class, () -> edit.save(vms, disks));
    }

    @Test public void capabilityAbsenceIsNotLegacyPermissionAndPartialApiFails() throws Exception {
        assertFalse(new ConfigurationClient(r -> new JSONObject().put("success", true)).supported());
        assertThrows(IOException.class, () -> new ConfigurationClient(r -> new JSONObject().put("success", true)).edit());
        for (String key : new String[]{"configuration_vm_disks", "configuration_vm_disks_future"})
            assertThrows(IOException.class, () -> new ConfigurationClient(r -> new JSONObject().put("success", true).put(key, "unknown")).supported());
        assertThrows(IOException.class, () -> new ConfigurationClient(r -> null).supported());
    }

    @Test public void restartBetweenSnapshotsRejectsSessionAndReleasesBoth() throws Exception {
        var backend = new Backend(); backend.mode = "restart";
        assertThrows(IOException.class, () -> new ConfigurationClient(backend).edit());
        assertEquals(2, backend.releases); assertEquals(0, backend.begins);
    }

    @Test public void commitFailuresNeverRetryAbortOrRebase() throws Exception {
        for (String mode : new String[]{"lost", "malformed", "missing-vms", "CONFLICT", "COMMIT_UNCERTAIN", "INTERNAL"}) {
            var backend = new Backend(); backend.mode = mode;
            var edit = new ConfigurationClient(backend).edit();
            if (mode.equals("CONFLICT") || mode.equals("COMMIT_UNCERTAIN"))
                assertEquals(mode, assertThrows(VmConfigClient.RemoteFailure.class, () -> edit.save(edit.vms(), edit.disks())).code);
            else assertThrows(VmConfigClient.CommitUnknown.class, () -> edit.save(edit.vms(), edit.disks()));
            assertThrows(IOException.class, () -> edit.save(edit.vms(), edit.disks()));
            assertEquals(1, backend.begins); assertEquals(1, backend.upload.commits); assertEquals(0, backend.upload.aborts);
            assertEquals(2, backend.snapshots);
        }
    }

    @Test public void appendFailureAbortsButStaleBeginDoesNotSubmit() throws Exception {
        for (String mode : new String[]{"append", "stale-begin"}) {
            var backend = new Backend(); backend.mode = mode;
            var edit = new ConfigurationClient(backend).edit();
            assertThrows(IOException.class, () -> edit.save(edit.vms(), edit.disks()));
            assertThrows(IOException.class, () -> edit.save(edit.vms(), edit.disks()));
            assertEquals(1, backend.begins); assertEquals(0, backend.upload.commits);
            assertEquals(mode.equals("append") ? 1 : 0, backend.upload.aborts);
        }
    }

    @Test public void oversizedDocumentRejectedBeforeUploadAndCanBeCorrected() throws Exception {
        var backend = new Backend(); var edit = new ConfigurationClient(backend).edit();
        var oversized = edit.disks().put("large", "x".repeat(DiskConfigClient.MAX_BYTES));
        assertThrows(IOException.class, () -> edit.save(edit.vms(), oversized));
        assertEquals(0, backend.begins);
        edit.save(edit.vms(), edit.disks());
        assertEquals(1, backend.upload.commits);
    }
}
