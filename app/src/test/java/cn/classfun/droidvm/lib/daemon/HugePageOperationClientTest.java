// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class HugePageOperationClientTest {
    private static final class MemoryJournal implements HugePageOperationClient.PendingStore {
        String value;
        String intent;
        public String load() { return value; }
        public boolean save(String value) { this.value = value; return true; }
        public boolean clear() { value = null; return true; }
        public String loadIntent() { return intent; }
        public boolean saveIntent(String value) { this.intent = value; return true; }
        public boolean clearIntent() { intent = null; return true; }
    }
    private interface Reply { JSONObject get(JSONObject request) throws Exception; }
    private static final class Fixture {
        final MemoryJournal journal = new MemoryJournal();
        final List<JSONObject> requests = new ArrayList<>();
        String fingerprint = "baseline-1";
        /** Extra hugepage_snapshot members (controls/settings/stats) for reconcile tests. */
        final JSONObject state = new JSONObject();
        Reply reply = request -> terminal(request, false, false, false);
        final VmConfigClient.Rpc rpc = request -> {
            // Transport assigns a different UUID every time and may mutate its envelope.
            request.put("request_id", UUID.randomUUID().toString());
            requests.add(new JSONObject(request.toString()));
            try {
                switch (request.getString("command")) {
                    case "capabilities": return new JSONObject().put("success", true)
                        .put("hugepages", HugePageOperationClient.CAPABILITY);
                    case "hugepage_snapshot": {
                        var body = new JSONObject().put("capability", HugePageOperationClient.CAPABILITY)
                            .put("fingerprint", fingerprint);
                        // keys()/names() only: the unit-test classpath uses android.jar's org.json.
                        var extras = state.names();
                        for (int i = 0; extras != null && i < extras.length(); i++) {
                            var key = extras.getString(i);
                            body.put(key, state.get(key));
                        }
                        return new JSONObject().put("success", true).put("snapshot", body);
                    }
                    default: return reply.get(request);
                }
            } catch (IOException | org.json.JSONException e) { throw e; }
            catch (Exception e) { throw new IOException(e); }
        };
        final HugePageOperationClient client = new HugePageOperationClient(rpc, journal);
        long count(String command) {
            return requests.stream().filter(r -> command.equals(r.optString("command"))).count();
        }
    }
    private static JSONObject terminal(JSONObject request, boolean failed, boolean applied, boolean uncertain) throws Exception {
        JSONObject result = new JSONObject().put("saved", false).put("applied", applied)
            .put("degraded", false).put("uncertain", uncertain).put("implementation", "test");
        JSONObject task = new JSONObject().put("epoch", "daemon-1").put("task_id", "task-1")
            .put("kind", "hugepage_operation").put("resource", "hugepages")
            .put("request_id", request.getString("requestId"))
            .put("state", failed ? "failed" : "succeeded").put("result", result);
        if (failed) task.put("error", new JSONObject().put("code", uncertain ? "COMMIT_UNCERTAIN" : "IO_ERROR")
            .put("message", "partial operation"));
        return new JSONObject().put("success", true).put("epoch", "daemon-1")
            .put("task_id", "task-1").put("task", task);
    }
    private static JSONObject operation() {
        return HugePageOperationClient.operation("runtime", "enabled", false);
    }

    @Test public void businessIdentityAndBaselineAreIndependentOfTheTransportEnvelope() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.client.execute(operation()).succeeded);
        JSONObject sent = f.requests.stream().filter(r -> "hugepage_operation_start".equals(r.optString("command")))
            .findFirst().orElseThrow();
        UUID.fromString(sent.getString("requestId"));
        assertNotEquals(sent.getString("request_id"), sent.getString("requestId"));
        assertEquals("baseline-1", sent.getJSONObject("expected").getString("fingerprint"));
        assertNull(f.journal.value);
        assertEquals(1, f.count("hugepage_operation_start"));
    }

    @Test public void lostReplySurvivesClientRecreationAndOnlyQueriesOriginalBusinessId() throws Exception {
        Fixture f = new Fixture();
        f.reply = request -> { throw new IOException("reply lost"); };
        assertTrue(assertThrows(HugePageOperationClient.Blocked.class,
            () -> f.client.execute(operation())).uncertain);
        String originalId = f.journal.value;
        HugePageOperationClient restarted = new HugePageOperationClient(f.rpc, f.journal);
        assertThrows(HugePageOperationClient.Blocked.class, () -> restarted.execute(operation()));
        f.reply = request -> {
            assertEquals("hugepage_operation_status", request.getString("command"));
            assertEquals(originalId, request.getString("requestId"));
            return terminal(request, false, true, false);
        };
        assertTrue(restarted.queryPending().succeeded);
        assertNull(f.journal.value);
        assertEquals(1, f.count("hugepage_operation_start"));
    }

    @Test public void uncertainAndPartialTerminalsAbortTheRestOfAComposition() throws Exception {
        for (boolean uncertain : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.reply = request -> terminal(request, true, true, uncertain);
            boolean[] continued = {false};
            HugePageOperationClient.Blocked blocked = assertThrows(HugePageOperationClient.Blocked.class, () -> {
                f.client.execute(operation());
                continued[0] = true;
                f.client.execute(HugePageOperationClient.operation("unload"));
            });
            assertFalse(continued[0]);
            assertTrue(blocked.canAcknowledge);
            assertNotNull(f.journal.value);
            assertEquals(1, f.count("hugepage_operation_start"));
        }
    }

    @Test public void malformedReceiptAndWrongIdentityNeverUnlockTheWriter() throws Exception {
        for (String corruption : List.of("request_id", "kind", "resource", "uncertain")) {
            Fixture f = new Fixture();
            f.reply = request -> {
                JSONObject response = terminal(request, false, true, false);
                JSONObject task = response.getJSONObject("task");
                if ("uncertain".equals(corruption)) task.getJSONObject("result").remove("uncertain");
                else task.put(corruption, "wrong-identity");
                return response;
            };
            HugePageOperationClient.Blocked blocked = assertThrows(HugePageOperationClient.Blocked.class,
                () -> f.client.execute(operation()));
            assertFalse(blocked.canAcknowledge);
            assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(operation()));
            assertEquals(1, f.count("hugepage_operation_start"));
            assertNotNull(f.journal.value);
        }
    }

    @Test public void missingTaskNeverAllowsAcknowledgmentOrAnotherSubmission() throws Exception {
        Fixture f = new Fixture();
        f.reply = request -> { throw new IOException("lost"); };
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(operation()));
        f.reply = request -> new JSONObject().put("success", false)
            .put("error", new JSONObject().put("code", "TASK_NOT_FOUND"));
        assertFalse(assertThrows(HugePageOperationClient.Blocked.class, f.client::queryPending).canAcknowledge);
        assertThrows(HugePageOperationClient.Blocked.class, f.client::readTerminalRecovery);
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.acknowledgeTerminalRecovery("baseline-1"));
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(operation()));
        assertNotNull(f.journal.value);
        assertEquals(1, f.count("hugepage_operation_start"));
    }

    @Test public void terminalUncertaintyRequiresFreshStateAndExplicitAcknowledgmentWithoutResubmission() throws Exception {
        Fixture f = new Fixture();
        f.reply = request -> terminal(request, true, true, true);
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(operation()));
        JSONObject reviewed = f.client.readTerminalRecovery();
        assertNotNull(f.journal.value);
        f.fingerprint = "changed";
        assertThrows(HugePageOperationClient.Blocked.class,
            () -> f.client.acknowledgeTerminalRecovery(reviewed.getString("fingerprint")));
        assertNotNull(f.journal.value);
        f.client.acknowledgeTerminalRecovery(f.client.readTerminalRecovery().getString("fingerprint"));
        assertNull(f.journal.value);
        assertEquals(1, f.count("hugepage_operation_start"));
    }

    @Test public void taskIdentityIsPinnedAcrossClientRecreation() throws Exception {
        Fixture f = new Fixture();
        f.reply = request -> terminal(request, true, true, true);
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(operation()));
        HugePageOperationClient restarted = new HugePageOperationClient(f.rpc, f.journal);
        f.reply = request -> {
            JSONObject response = terminal(request, true, true, true).put("epoch", "daemon-2");
            response.getJSONObject("task").put("epoch", "daemon-2");
            return response;
        };
        assertFalse(assertThrows(HugePageOperationClient.Blocked.class, restarted::queryPending).canAcknowledge);
        assertNotNull(f.journal.value);
    }

    @Test public void definiteAdmissionRejectionDoesNotLeaveAPermanentPendingRequest() {
        for (String code : new String[]{"RESOURCE_EXHAUSTED", "UNAVAILABLE"}) {
            Fixture f = new Fixture();
            f.reply = request -> new JSONObject().put("success", false)
                .put("error", new JSONObject().put("code", code));
            assertFalse(assertThrows(HugePageOperationClient.Blocked.class,
                () -> f.client.execute(operation())).uncertain);
            assertNull(f.journal.value);
            f.reply = request -> terminal(request, false, true, false);
            assertTrue(f.client.execute(operation()).succeeded);
        }
    }

    /* ---- reconciliation against the module's own state ------------------ */

    private static JSONObject notRetained() throws Exception {
        return new JSONObject().put("success", false)
            .put("error", new JSONObject().put("code", "TASK_NOT_FOUND"));
    }

    private static JSONObject controls(long want) throws Exception {
        return new JSONObject().put("pool_want", want).put("pool_want_with_cma", 0);
    }

    private static JSONObject stats(long avail, long want, long active) throws Exception {
        return new JSONObject().put("acquire_active", active).put("pool_avail", avail).put("pool_want", want);
    }

    /** A daemon that forgot the task must not block the screen forever. */
    @Test public void lostTaskRecordSettlesAsDoneFromModuleStateAndAllowsANewOperation() throws Exception {
        Fixture f = new Fixture();
        f.reply = request -> { throw new IOException("reply lost"); };
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(operation()));
        assertNotNull(f.journal.value);
        assertNotNull(f.journal.intent);
        HugePageOperationClient restarted = new HugePageOperationClient(f.rpc, f.journal);
        assertThrows(HugePageOperationClient.Blocked.class, () -> restarted.execute(operation()));

        // The module is not loaded, which is exactly what runtime/enabled=false asked for.
        f.state.put("loaded", false);
        f.reply = request -> notRetained();
        var reconciled = restarted.reconcilePending();
        assertNotNull(reconciled);
        assertEquals(HugePageOperationClient.Resolution.DONE, reconciled.resolution);
        assertNull(f.journal.value);
        assertNull(f.journal.intent);
        assertEquals(1, f.count("hugepage_operation_start"));

        f.reply = request -> terminal(request, false, true, false);
        assertTrue(restarted.execute(operation()).succeeded);
    }

    /** Intent not applied, module idle: settle as resumed instead of staying blocked. */
    @Test public void unappliedIntentOnAnIdleModuleResolvesAsResumed() throws Exception {
        Fixture f = new Fixture();
        var targets = HugePageOperationClient.operation(
            "targets", "poolPages", 4505L, "cmaPages", null, "persist", true);
        f.reply = request -> { throw new IOException("reply lost"); };
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(targets));
        f.reply = request -> notRetained();
        // Far from the request: the resize genuinely did not happen.
        f.state.put("controls", controls(4400));
        f.state.put("settings", new JSONObject().put("pool_want", "4400"));
        f.state.put("stats", stats(4400, 4400, 0));
        var reconciled = f.client.reconcilePending();
        assertEquals(HugePageOperationClient.Resolution.RESUMED, reconciled.resolution);
        assertNull(f.journal.value);
    }

    /** The module's pageblock rounding (4505 asked, 4504 built) is a completed resize. */
    @Test public void pageblockRoundingCountsAsDone() throws Exception {
        Fixture f = new Fixture();
        var targets = HugePageOperationClient.operation(
            "targets", "poolPages", 4505L, "cmaPages", null, "persist", true);
        f.reply = request -> { throw new IOException("reply lost"); };
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(targets));
        f.reply = request -> notRetained();
        f.state.put("controls", controls(4504));
        f.state.put("settings", new JSONObject().put("pool_want", "4505"));
        f.state.put("stats", stats(4504, 4504, 0));
        var reconciled = f.client.reconcilePending();
        assertEquals(HugePageOperationClient.Resolution.DONE, reconciled.resolution);
        assertNull(f.journal.value);
    }

    /** A busy module can still change on its own: never claim an outcome there. */
    @Test public void busyModuleKeepsTheRequestPending() throws Exception {
        Fixture f = new Fixture();
        var acquire = HugePageOperationClient.operation("acquire", "mode", 1);
        f.reply = request -> { throw new IOException("reply lost"); };
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(acquire));
        f.reply = request -> notRetained();
        f.state.put("stats", stats(0, 4504, 1));
        var reconciled = f.client.reconcilePending();
        assertFalse(reconciled.settled());
        assertNotNull(f.journal.value);
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(operation()));
    }

    /** The explicit escape hatch always lifts the block, and never resubmits. */
    @Test public void forceClearLiftsTheBlockWithoutResubmitting() throws Exception {
        Fixture f = new Fixture();
        f.reply = request -> { throw new IOException("reply lost"); };
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(operation()));
        f.reply = request -> notRetained();
        f.client.forceClearPending();
        assertNull(f.journal.value);
        assertNull(f.journal.intent);
        assertFalse(f.client.hasPending());
        assertEquals(1, f.count("hugepage_operation_start"));
        f.reply = request -> terminal(request, false, true, false);
        assertTrue(f.client.execute(operation()).succeeded);
    }

    /** A running daemon task is not a settlement condition either. */
    @Test public void runningTaskStaysPending() throws Exception {
        Fixture f = new Fixture();
        f.reply = request -> { throw new IOException("reply lost"); };
        assertThrows(HugePageOperationClient.Blocked.class, () -> f.client.execute(operation()));
        f.reply = request -> {
            var task = terminal(request, false, false, false).getJSONObject("task").put("state", "running");
            return new JSONObject().put("success", true).put("epoch", "daemon-1")
                .put("task_id", "task-1").put("task", task);
        };
        assertFalse(f.client.reconcilePending().settled());
        assertNotNull(f.journal.value);
        assertEquals(1, f.count("hugepage_operation_start"));
    }
}
