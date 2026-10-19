// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class DiskDefaultsClientTest {
    private static JSONObject values(String compression, String cpus) throws Exception {
        return new JSONObject().put("optimizeCompression", compression).put("qemuImgCpus", new JSONArray(cpus));
    }
    private static final class MemoryStore implements DiskDefaultsClient.Store {
        String pending;
        DiskDefaultsClient.Snapshot mirror;
        public String load() { return pending; }
        public boolean save(String value) { pending = value; return true; }
        public boolean clear() { pending = null; return true; }
        public JSONObject legacy() throws org.json.JSONException {
            return new JSONObject().put("vm_optimize_compression", "none").put("qemu_img_cpu_affinity", "4,5")
                .put("vm_hugepage_auto_reserve", false).put("usb_acm_enable", false).put("language", "ar");
        }
        public boolean mirror(DiskDefaultsClient.Snapshot snapshot) { mirror = snapshot; return true; }
    }
    private interface Reply { JSONObject get(JSONObject request) throws Exception; }
    private static final class Fixture {
        final MemoryStore store = new MemoryStore();
        final List<JSONObject> requests = new ArrayList<>();
        boolean initialized = true, supported = true;
        String revision = "disk-1";
        JSONObject current = values("ask", "[]"), admitted;
        Reply reply = request -> terminal(request, "succeeded", true, false);
        final VmConfigClient.Rpc rpc = request -> {
            request.put("request_id", UUID.randomUUID().toString()); requests.add(new JSONObject(request.toString()));
            try {
                switch (request.getString("command")) {
                    case "capabilities": {
                        var response = new JSONObject().put("success", true).put("host_preferences", HostPreferencesClient.CAPABILITY);
                        if (supported) response.put("disk_defaults", DiskDefaultsClient.CAPABILITY);
                        return response;
                    }
                    case "host_preferences_snapshot": return new JSONObject().put("success", true).put("snapshot", new JSONObject()
                        .put("capability", HostPreferencesClient.CAPABILITY).put("expected", new JSONObject().put("revision", "host-1"))
                        .put("values", new JSONObject().put(HostPreferencesClient.AUTO_RESERVE, true).put(HostPreferencesClient.OPTIMIZE_SDCARD, true)
                            .put(HostPreferencesClient.CLEAR_LOGS, false)).put("initialized", true).put("uncertain", false));
                    case "disk_defaults_snapshot": return new JSONObject().put("success", true).put("snapshot", snapshot());
                    case "set_app_config": {
                        assertFalse(request.getJSONObject("config").has("vm_hugepage_auto_reserve"));
                        assertEquals("ar", request.getJSONObject("config").getString("language"));
                        current = values("none", "[4,5]"); revision = "migrated-1"; initialized = true;
                        return new JSONObject().put("success", true).put("disk_defaults", snapshot());
                    }
                    case "disk_defaults_operation_start": admitted = new JSONObject(request.toString()); return reply.get(request);
                    case "disk_defaults_operation_status": return reply.get(request);
                    default: throw new IOException("Unexpected command");
                }
            } catch (IOException | org.json.JSONException error) { throw error; }
            catch (Exception error) { throw new IOException(error); }
        };
        final DiskDefaultsClient client = new DiskDefaultsClient(rpc, store);
        Fixture() throws Exception {}
        JSONObject snapshot() throws Exception {
            return new JSONObject().put("capability", DiskDefaultsClient.CAPABILITY)
                .put("expected", new JSONObject().put("revision", revision)).put("values", current)
                .put("initialized", initialized).put("uncertain", false);
        }
        JSONObject terminal(JSONObject request, String state, boolean saved, boolean uncertain) throws Exception {
            var resultValues = new JSONObject(current.toString());
            if (saved) {
                var changes = admitted.getJSONObject("changes"); var keys = changes.keys();
                while (keys.hasNext()) { String key = keys.next(); resultValues.put(key, changes.get(key)); }
                if (changes.has("qemuImgCpus")) {
                    var sorted = new java.util.TreeSet<Integer>();
                    var input = changes.getJSONArray("qemuImgCpus"); for (int i = 0; i < input.length(); i++) sorted.add(input.getInt(i));
                    resultValues.put("qemuImgCpus", new JSONArray(sorted));
                }
            }
            var result = new JSONObject().put("expected", admitted.getJSONObject("expected")).put("values", resultValues)
                .put("saved", saved).put("uncertain", uncertain).put("revision", saved ? "saved-2" : JSONObject.NULL);
            var task = new JSONObject().put("kind", "disk_defaults").put("resource", "disk-defaults")
                .put("epoch", "daemon-1").put("task_id", "task-1").put("request_id", request.getString("requestId"))
                .put("state", state).put("result", result);
            if (!saved) task.put("error", new JSONObject().put("code", uncertain ? "COMMIT_UNCERTAIN" : "CONFLICT"));
            return new JSONObject().put("success", true).put("epoch", "daemon-1").put("task_id", "task-1").put("task", task);
        }
        long count(String command) { return requests.stream().filter(r -> command.equals(r.optString("command"))).count(); }
    }

    @Test public void migrationPreservesOtherDomainsAndReconnectReadsWebDefaults() throws Exception {
        var f = new Fixture(); f.initialized = false;
        assertEquals("4,5", f.client.synchronizeLegacy().cpuCsv());
        assertEquals(1, f.count("set_app_config"));
        f.current = values("ask", "[]"); f.revision = "web-2";
        var snapshot = f.client.forNewOperation();
        assertEquals("web-2", snapshot.revision); assertEquals("", snapshot.cpuCsv());
        assertEquals("ask", f.store.mirror.optimizeCompression); assertEquals(1, f.count("set_app_config"));
    }

    @Test public void normalizedCpuReceiptAcceptedAndConflictNeverRebased() throws Exception {
        var f = new Fixture(); var baseline = f.client.refresh();
        assertTrue(f.client.patch(baseline, new JSONObject().put("qemuImgCpus", new JSONArray("[5,4]"))).succeeded);
        assertFalse(f.client.hasPending());
        f.revision = "web-3"; f.reply = request -> f.terminal(request, "failed", false, false);
        assertEquals("CONFLICT", f.client.patch(baseline, new JSONObject().put("optimizeCompression", "none")).errorCode);
        assertEquals("disk-1", f.admitted.getJSONObject("expected").getString("revision"));
        assertEquals(2, f.count("disk_defaults_operation_start")); assertFalse(f.client.hasPending());
    }

    @Test public void unknownSavePersistsIdentityAndRestartOnlyQueriesIt() throws Exception {
        var f = new Fixture(); var baseline = f.client.refresh();
        f.reply = request -> { throw new IOException("Lost reply"); };
        assertTrue(assertThrows(DiskDefaultsClient.Blocked.class, () -> f.client.patch(baseline,
            new JSONObject().put("optimizeCompression", "none"))).uncertain);
        String id = new JSONObject(f.store.pending).getString("requestId");
        var restarted = new DiskDefaultsClient(f.rpc, f.store);
        assertThrows(DiskDefaultsClient.Blocked.class, () -> restarted.patch(baseline, new JSONObject().put("qemuImgCpus", new JSONArray())));
        f.reply = request -> {
            assertEquals("disk_defaults_operation_status", request.getString("command"));
            assertEquals(id, request.getString("requestId"));
            return f.terminal(request, "succeeded", true, false);
        };
        assertTrue(restarted.queryPending().succeeded); assertFalse(restarted.hasPending());
        assertEquals(1, f.count("disk_defaults_operation_start"));
    }

    @Test public void malformedAndUncertainStatesCannotBecomeWritableOrFallBack() throws Exception {
        var f = new Fixture();
        for (String cpus : new String[]{"[1,1]", "[-1]", "[1024]", "[1.5]", "[\"1\"]"}) {
            var invalid = f.snapshot().put("values", values("ask", cpus));
            assertThrows(IOException.class, () -> new DiskDefaultsClient.Snapshot(invalid));
        }
        var baseline = f.client.refresh(); f.reply = request -> f.terminal(request, "failed", false, true);
        assertTrue(assertThrows(DiskDefaultsClient.Blocked.class, () -> f.client.patch(baseline,
            new JSONObject().put("optimizeCompression", "none"))).uncertain);
        assertTrue(f.client.hasPending());
        var disconnected = new DiskDefaultsClient(request -> { throw new IOException("Capabilities unavailable"); }, new MemoryStore());
        assertThrows(IOException.class, disconnected::forNewOperation);
        var old = new Fixture(); old.supported = false;
        assertTrue(assertThrows(DiskDefaultsClient.Blocked.class, old.client::forNewOperation).unsupported);
        assertEquals(0, old.count("set_app_config"));
    }
}
