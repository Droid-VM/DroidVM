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

public class UsbAcmClientTest {
    private static final class MemoryStore implements UsbAcmClient.Store {
        String pending;
        UsbAcmClient.Snapshot mirror;
        public String load() { return pending; }
        public boolean save(String value) { pending = value; return true; }
        public boolean clear() { pending = null; return true; }
        public JSONObject legacy() throws org.json.JSONException {
            return new JSONObject().put("usb_acm_enable", true).put("usb_acm_ports", 2)
                .put("vm_auto_console", true).put(HostPreferencesClient.AUTO_RESERVE, false);
        }
        public boolean mirror(UsbAcmClient.Snapshot value) { mirror = value; return true; }
    }
    private interface Reply { JSONObject get(JSONObject request) throws Exception; }
    private static final class Fixture {
        final MemoryStore store = new MemoryStore();
        final List<JSONObject> requests = new ArrayList<>();
        boolean supported = true, initialized = true, enabled = false, applied = true;
        int ports = 4;
        String revision = "initial-1";
        String reason;
        JSONObject admitted;
        Reply reply = request -> terminal(request, "succeeded", true, true, false);
        final VmConfigClient.Rpc rpc = request -> {
            request.put("request_id", UUID.randomUUID().toString()); requests.add(new JSONObject(request.toString()));
            try {
                switch (request.getString("command")) {
                    case "capabilities": {
                        var caps = new JSONObject().put("success", true).put("host_preferences", HostPreferencesClient.CAPABILITY);
                        if (supported) caps.put("usb_acm", UsbAcmClient.CAPABILITY); return caps;
                    }
                    case "host_preferences_snapshot": return new JSONObject().put("success", true).put("snapshot", new JSONObject()
                        .put("capability", HostPreferencesClient.CAPABILITY).put("expected", new JSONObject().put("revision", "host-1"))
                        .put("values", new JSONObject().put(HostPreferencesClient.AUTO_RESERVE, true).put(HostPreferencesClient.OPTIMIZE_SDCARD, true)
                            .put(HostPreferencesClient.CLEAR_LOGS, false)).put("initialized", true).put("uncertain", false));
                    case "usb_acm_snapshot": return new JSONObject().put("success", true).put("snapshot", snapshot());
                    case "set_app_config": {
                        if (!initialized) { var config = request.getJSONObject("config"); enabled = config.getBoolean("usb_acm_enable"); ports = config.getInt("usb_acm_ports"); initialized = true; revision = "migrated-1"; }
                        return new JSONObject().put("success", true).put("usb_acm", snapshot());
                    }
                    case "usb_acm_operation_start": admitted = new JSONObject(request.toString()); return reply.get(request);
                    case "usb_acm_operation_status": return reply.get(request);
                    default: throw new IOException("Unexpected command");
                }
            } catch (IOException | org.json.JSONException error) { throw error; }
            catch (Exception error) { throw new IOException(error); }
        };
        final UsbAcmClient client = new UsbAcmClient(rpc, store);
        JSONObject desired() throws Exception { return new JSONObject().put("enabled", enabled).put("ports", ports); }
        JSONObject live() throws Exception {
            return new JSONObject().put("available", true).put("applied", applied).put("udcBound", true)
                .put("ports", enabled && applied ? ports : 0).put("owners", new JSONArray()).put("reason", reason == null ? JSONObject.NULL : reason);
        }
        JSONObject snapshot() throws Exception {
            return new JSONObject().put("capability", UsbAcmClient.CAPABILITY).put("expected", new JSONObject().put("revision", revision))
                .put("desired", desired()).put("initialized", initialized).put("persistenceUncertain", false).put("live", live());
        }
        JSONObject terminal(JSONObject request, String state, boolean saved, boolean appliedResult, boolean uncertain) throws Exception {
            JSONObject operation = admitted.getJSONObject("operation");
            if (saved && "configure".equals(operation.getString("kind"))) {
                enabled = operation.getBoolean("enabled"); ports = operation.getInt("ports"); revision = "configured-2";
            }
            applied = appliedResult;
            var result = new JSONObject().put("expected", admitted.getJSONObject("expected")).put("desired", desired())
                .put("saved", saved).put("applied", appliedResult).put("uncertain", uncertain).put("revision", saved ? revision : JSONObject.NULL).put("live", live());
            var task = new JSONObject().put("epoch", "daemon-1").put("task_id", "task-1").put("kind", "usb_acm").put("resource", "usb-acm")
                .put("request_id", request.getString("requestId")).put("state", state).put("result", result);
            if (!"succeeded".equals(state)) task.put("error", new JSONObject().put("code", uncertain ? "COMMIT_UNCERTAIN" : "IO_ERROR"));
            return new JSONObject().put("success", true).put("epoch", "daemon-1").put("task_id", "task-1").put("task", task);
        }
        long count(String command) { return requests.stream().filter(value -> command.equals(value.optString("command"))).count(); }
    }

    @Test public void firstMigrationPreservesLocalFieldsAndNeverOverwritesOtherOwners() throws Exception {
        Fixture f = new Fixture(); f.initialized = false;
        assertEquals(2, f.client.synchronizeLegacy().ports);
        var config = f.requests.stream().filter(request -> "set_app_config".equals(request.optString("command")))
            .findFirst().orElseThrow().getJSONObject("config");
        assertTrue(config.getBoolean("vm_auto_console")); assertFalse(config.has(HostPreferencesClient.AUTO_RESERVE));
        f.enabled = false; f.ports = 5; f.revision = "web-3";
        assertEquals(5, f.client.synchronizeLegacy().ports); assertFalse(f.store.mirror.enabled);
        assertEquals(1, f.count("set_app_config"));
    }

    @Test public void savedButUnappliedResultUnlocksOnlyAnExplicitNewApply() throws Exception {
        Fixture f = new Fixture(); var baseline = f.client.refresh();
        f.reply = request -> f.terminal(request, "failed", true, false, false);
        var receipt = f.client.configure(baseline, true, 3);
        assertTrue(receipt.saved); assertFalse(receipt.applied); assertNull(f.store.pending);
        assertEquals("initial-1", f.admitted.getJSONObject("expected").getString("revision"));
        assertEquals(1, f.count("usb_acm_operation_start"));
        var saved = f.client.refresh(); assertEquals("configured-2", saved.revision);
        f.reply = request -> f.terminal(request, "succeeded", true, true, false);
        assertTrue(f.client.apply(saved).applied);
        assertEquals("apply", f.admitted.getJSONObject("operation").getString("kind"));
        assertEquals("configured-2", f.admitted.getJSONObject("expected").getString("revision"));
        assertEquals(2, f.count("usb_acm_operation_start"));
    }

    @Test public void lostReceiptPersistsOriginalRequestAndQueriesWithoutReconfigure() throws Exception {
        Fixture f = new Fixture(); var baseline = f.client.refresh();
        f.reply = request -> { throw new IOException("lost"); };
        assertTrue(assertThrows(UsbAcmClient.Blocked.class, () -> f.client.configure(baseline, true, 3)).uncertain);
        String id = new JSONObject(f.store.pending).getString("requestId");
        assertNotEquals(id, f.admitted.getString("request_id"));
        var restored = new UsbAcmClient(f.rpc, f.store);
        assertThrows(UsbAcmClient.Blocked.class, () -> restored.apply(baseline));
        f.reply = request -> {
            assertEquals("usb_acm_operation_status", request.getString("command")); assertEquals(id, request.getString("requestId"));
            return f.terminal(request, "succeeded", true, true, false);
        };
        assertTrue(restored.queryPending().applied); assertNull(f.store.pending);
        assertEquals(1, f.count("usb_acm_operation_start"));
    }

    @Test public void uncertainWrongIdentityAndUnsupportedNeverPermitAnotherWrite() throws Exception {
        for (boolean mismatch : new boolean[]{false, true}) {
            Fixture f = new Fixture(); var baseline = f.client.refresh();
            f.reply = request -> {
                var response = f.terminal(request, "failed", false, false, !mismatch);
                if (mismatch) response.getJSONObject("task").put("request_id", "other"); return response;
            };
            assertTrue(assertThrows(UsbAcmClient.Blocked.class, () -> f.client.configure(baseline, true, 3)).uncertain);
            assertThrows(UsbAcmClient.Blocked.class, () -> f.client.apply(baseline)); assertNotNull(f.store.pending);
            assertEquals(1, f.count("usb_acm_operation_start"));
        }
        Fixture old = new Fixture(); old.supported = false;
        assertTrue(assertThrows(UsbAcmClient.Blocked.class, old.client::synchronizeLegacy).unsupported);
        assertEquals(0, old.count("set_app_config")); assertEquals(0, old.count("usb_acm_operation_start"));
        old.supported = true;
        for (String reason : new String[]{"application-outcome-unknown", "persistence-outcome-unknown"}) {
            old.reason = reason; var unknown = old.client.refresh(); assertFalse(unknown.writable);
            assertThrows(UsbAcmClient.Blocked.class, () -> old.client.apply(unknown));
        }
        old.reason = "application-failed"; assertTrue(old.client.refresh().writable);
    }
}
