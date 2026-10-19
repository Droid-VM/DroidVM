// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class HostPreferencesClientTest {
    private static JSONObject values(boolean reserve, boolean optimize, boolean clear) throws Exception {
        return new JSONObject().put(HostPreferencesClient.AUTO_RESERVE, reserve)
            .put(HostPreferencesClient.OPTIMIZE_SDCARD, optimize).put(HostPreferencesClient.CLEAR_LOGS, clear);
    }
    private static final class MemoryStore implements HostPreferencesClient.Store {
        String pending;
        JSONObject legacyValues;
        HostPreferencesClient.Snapshot mirror;
        MemoryStore() throws Exception { legacyValues = values(false, false, true); }
        public String load() { return pending; }
        public boolean save(String value) { pending = value; return true; }
        public boolean clear() { pending = null; return true; }
        public JSONObject legacy() throws org.json.JSONException { return new JSONObject(legacyValues.toString()); }
        public boolean mirror(HostPreferencesClient.Snapshot snapshot) { mirror = snapshot; return true; }
    }
    private interface Reply { JSONObject get(JSONObject request) throws Exception; }
    private static final class Fixture {
        final MemoryStore store = new MemoryStore();
        final List<JSONObject> requests = new ArrayList<>();
        String revision = "baseline-1";
        boolean supported = true, initialized = true, uncertain = false;
        JSONObject current = values(true, true, false);
        JSONObject admitted;
        Reply reply = request -> terminal(request, "succeeded", true, false, null);
        final VmConfigClient.Rpc rpc = request -> {
            request.put("request_id", UUID.randomUUID().toString());
            requests.add(new JSONObject(request.toString()));
            try {
                switch (request.getString("command")) {
                    case "capabilities": {
                        JSONObject result = new JSONObject().put("success", true);
                        if (supported) result.put("host_preferences", HostPreferencesClient.CAPABILITY);
                        return result;
                    }
                    case "host_preferences_snapshot": return new JSONObject().put("success", true).put("snapshot", snapshot());
                    case "set_app_config": {
                        if (!initialized && request.getJSONObject("config").has(HostPreferencesClient.AUTO_RESERVE)) {
                            JSONObject config = request.getJSONObject("config");
                            current = values(config.getBoolean(HostPreferencesClient.AUTO_RESERVE), config.getBoolean(HostPreferencesClient.OPTIMIZE_SDCARD), config.getBoolean(HostPreferencesClient.CLEAR_LOGS));
                            initialized = true; revision = "migrated-1";
                        }
                        return new JSONObject().put("success", true).put("host_preferences", snapshot());
                    }
                    case "host_preferences_operation_start": admitted = new JSONObject(request.toString()); return reply.get(request);
                    case "host_preferences_operation_status": return reply.get(request);
                    case "vm_start_configured": return new JSONObject().put("success", true).put("vm_id", request.getString("vm_id")).put("revision", request.getString("revision"));
                    default: throw new IOException("Unexpected command");
                }
            } catch (IOException | org.json.JSONException error) { throw error; }
            catch (Exception error) { throw new IOException(error); }
        };
        final HostPreferencesClient client = new HostPreferencesClient(rpc, store);
        Fixture() throws Exception {}
        JSONObject snapshot() throws Exception {
            return new JSONObject().put("capability", HostPreferencesClient.CAPABILITY)
                .put("expected", new JSONObject().put("revision", revision)).put("values", current)
                .put("initialized", initialized).put("uncertain", uncertain);
        }
        JSONObject terminal(JSONObject request, String state, boolean saved, boolean uncertainResult, String error) throws Exception {
            JSONObject original = admitted == null ? request : admitted;
            JSONObject resultValues = new JSONObject(current.toString());
            if (saved) {
                JSONObject changes = original.getJSONObject("changes"); var keys = changes.keys();
                while (keys.hasNext()) { String key = keys.next(); resultValues.put(key, changes.get(key)); }
                current = resultValues; revision = "saved-2";
            }
            JSONObject result = new JSONObject().put("expected", original.getJSONObject("expected"))
                .put("values", resultValues).put("saved", saved).put("uncertain", uncertainResult)
                .put("revision", saved ? "saved-2" : JSONObject.NULL);
            JSONObject task = new JSONObject().put("epoch", "daemon-1").put("task_id", "task-1")
                .put("kind", "host_preferences").put("resource", "host-preferences")
                .put("request_id", request.getString("requestId")).put("state", state).put("result", result);
            if (error != null) task.put("error", new JSONObject().put("code", error));
            return new JSONObject().put("success", true).put("epoch", "daemon-1").put("task_id", "task-1").put("task", task);
        }
        long count(String command) { return requests.stream().filter(value -> command.equals(value.optString("command"))).count(); }
    }

    @Test public void legacyMigrationRunsOnceAndReconnectMirrorsExternalChanges() throws Exception {
        Fixture f = new Fixture(); f.initialized = false; f.store.legacyValues.put("usb_acm_enabled", true);
        assertFalse(f.client.synchronizeLegacy().value(HostPreferencesClient.AUTO_RESERVE));
        assertEquals(1, f.count("set_app_config"));
        assertTrue(f.requests.stream().filter(request -> "set_app_config".equals(request.optString("command")))
            .findFirst().orElseThrow().getJSONObject("config").getBoolean("usb_acm_enabled"));
        f.current = values(true, true, false); f.revision = "web-2";
        assertTrue(f.client.synchronizeLegacy().value(HostPreferencesClient.AUTO_RESERVE));
        assertEquals("web-2", f.store.mirror.revision);
        assertEquals(1, f.count("set_app_config"));
    }

    @Test public void patchUsesReviewedVersionAndConflictDoesNotReplay() throws Exception {
        Fixture f = new Fixture(); var baseline = f.client.refresh(); f.revision = "web-2";
        f.reply = request -> f.terminal(request, "failed", false, false, "CONFLICT");
        var receipt = f.client.patch(baseline, HostPreferencesClient.AUTO_RESERVE, false);
        assertFalse(receipt.succeeded); assertEquals("CONFLICT", receipt.errorCode);
        assertEquals("baseline-1", f.admitted.getJSONObject("expected").getString("revision"));
        assertFalse(f.admitted.getJSONObject("changes").getBoolean(HostPreferencesClient.AUTO_RESERVE));
        assertNull(f.store.pending); assertEquals("web-2", f.client.refresh().revision);
        assertEquals(1, f.count("host_preferences_operation_start"));
    }

    @Test public void lostReplySurvivesRestartAndQueriesOnlyOriginalRequest() throws Exception {
        Fixture f = new Fixture(); var baseline = f.client.refresh();
        f.reply = request -> { throw new IOException("reply lost"); };
        assertTrue(assertThrows(HostPreferencesClient.Blocked.class,
            () -> f.client.patch(baseline, HostPreferencesClient.OPTIMIZE_SDCARD, false)).uncertain);
        String id = new JSONObject(f.store.pending).getString("requestId");
        assertNotEquals(id, f.admitted.getString("request_id"));
        var restarted = new HostPreferencesClient(f.rpc, f.store);
        assertThrows(HostPreferencesClient.Blocked.class, () -> restarted.patch(baseline, HostPreferencesClient.CLEAR_LOGS, true));
        f.reply = request -> {
            assertEquals("host_preferences_operation_status", request.getString("command"));
            assertEquals(id, request.getString("requestId"));
            return f.terminal(request, "succeeded", true, false, null);
        };
        assertTrue(restarted.queryPending().succeeded);
        assertNull(f.store.pending); assertEquals(1, f.count("host_preferences_operation_start"));
    }

    @Test public void uncertainAndMismatchedReceiptsKeepNewWritesBlocked() throws Exception {
        for (boolean mismatch : new boolean[]{false, true}) {
            Fixture f = new Fixture(); var baseline = f.client.refresh();
            f.reply = request -> {
                var response = f.terminal(request, "failed", false, !mismatch, mismatch ? "CONFLICT" : "COMMIT_UNCERTAIN");
                if (mismatch) response.getJSONObject("task").put("request_id", "other-request");
                return response;
            };
            assertTrue(assertThrows(HostPreferencesClient.Blocked.class,
                () -> f.client.patch(baseline, HostPreferencesClient.CLEAR_LOGS, true)).uncertain);
            assertNotNull(f.store.pending);
            assertThrows(HostPreferencesClient.Blocked.class, () -> f.client.patch(baseline, HostPreferencesClient.AUTO_RESERVE, false));
            assertEquals(1, f.count("host_preferences_operation_start"));
        }
    }

    @Test public void unsupportedOrUncertainOwnerCannotAcceptLocalChanges() throws Exception {
        Fixture f = new Fixture(); f.supported = false;
        assertTrue(assertThrows(HostPreferencesClient.Blocked.class, f.client::synchronizeLegacy).unsupported);
        assertEquals(0, f.count("set_app_config")); assertNull(f.store.mirror);
        f.client.synchronizeAppConfig();
        var legacy = f.requests.get(f.requests.size() - 1).getJSONObject("config");
        assertFalse(legacy.getBoolean(HostPreferencesClient.AUTO_RESERVE));
        assertFalse(legacy.getBoolean(HostPreferencesClient.OPTIMIZE_SDCARD));
        assertTrue(legacy.getBoolean(HostPreferencesClient.CLEAR_LOGS));
        var disconnected = new HostPreferencesClient(request -> {
            assertEquals("capabilities", request.getString("command"));
            throw new IOException("capability unavailable");
        }, f.store);
        assertThrows(IOException.class, disconnected::synchronizeAppConfig);
        f.supported = true; f.uncertain = true; var uncertain = f.client.refresh();
        assertFalse(uncertain.writable); assertNull(f.store.mirror);
        assertThrows(HostPreferencesClient.Blocked.class, () -> f.client.patch(uncertain, HostPreferencesClient.CLEAR_LOGS, true));
        assertEquals(0, f.count("host_preferences_operation_start"));
    }

    @Test public void configuredStartUsesNativeSharedDefaultInsteadOfStaleFalse() throws Exception {
        Fixture f = new Fixture(); var client = new VmConfigClient(f.rpc);
        var metadata = new JSONObject().put("revision", "vm-revision").put("persisted", true).put("recovered", false).put("durability_uncertain", false);
        var snapshot = new VmConfigClient.Snapshot(metadata, "epoch", "cursor", "{\"vms\":[{\"id\":\"vm-1\"}]}");
        client.startWithSharedDefaults(snapshot, "vm-1", new JSONObject(), "", false);
        var request = f.requests.get(f.requests.size() - 1);
        assertEquals("vm_start_configured", request.getString("command"));
        assertFalse(request.has("clear_logs_before_start"));
        f.supported = false;
        client.startWithSharedDefaults(snapshot, "vm-1", new JSONObject(), "", true);
        assertTrue(f.requests.get(f.requests.size() - 1).getBoolean("clear_logs_before_start"));
    }
}
