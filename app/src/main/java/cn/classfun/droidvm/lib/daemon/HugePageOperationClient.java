// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;

/** Worker-thread client. Only the native owner may mutate the huge-page module or settings. */
public final class HugePageOperationClient {
    public static final String CAPABILITY = "coordinated-hugepages-v1";
    private static final HugePageOperationClient INSTANCE =
        new HugePageOperationClient(DaemonConnection.getInstance()::request);
    private final VmConfigClient.Rpc rpc;
    interface PendingStore {
        String load();
        boolean save(String requestId);
        boolean clear();
    }
    private PendingStore journal;
    private volatile String pending;
    private String taskEpoch, taskId;

    public HugePageOperationClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }
    HugePageOperationClient(VmConfigClient.Rpc rpc, PendingStore journal) {
        this.rpc = rpc;
        this.journal = journal;
        restorePending(journal.load());
    }
    public static HugePageOperationClient getInstance() { return INSTANCE; }
    public boolean hasPending() { return pending != null; }
    public static void initialize(Context context) {
        synchronized (INSTANCE) {
            if (INSTANCE.journal != null) return;
            var preferences = context.getApplicationContext()
                .getSharedPreferences("hugepage_operations", Context.MODE_PRIVATE);
            INSTANCE.journal = new PendingStore() {
                public String load() { return preferences.getString("requestId", null); }
                public boolean save(String id) { return preferences.edit().putString("requestId", id).commit(); }
                public boolean clear() { return preferences.edit().remove("requestId").commit(); }
            };
            INSTANCE.restorePending(INSTANCE.journal.load());
        }
    }

    /** Stops the caller's entire composition: it must never fall back to another write. */
    public static final class Blocked extends RuntimeException {
        public final boolean uncertain;
        public final boolean canAcknowledge;
        Blocked(String message, boolean uncertain, Throwable cause) {
            this(message, uncertain, false, cause);
        }
        Blocked(String message, boolean uncertain, boolean canAcknowledge, Throwable cause) {
            super(message, cause);
            this.uncertain = uncertain;
            this.canAcknowledge = canAcknowledge;
        }
    }

    public static final class Receipt {
        public final boolean succeeded, saved, applied, degraded;
        public final String implementation, error;
        public final JSONObject snapshot;
        Receipt(JSONObject task) {
            succeeded = "succeeded".equals(task.optString("state"));
            JSONObject result = task.optJSONObject("result");
            saved = result != null && result.optBoolean("saved");
            applied = result != null && result.optBoolean("applied");
            degraded = result != null && result.optBoolean("degraded");
            implementation = result == null ? "native" : result.optString("implementation", "native");
            snapshot = result == null ? null : result.optJSONObject("snapshot");
            JSONObject failure = task.optJSONObject("error");
            error = failure == null ? "" : failure.optString("code") + ": " + failure.optString("message");
        }
    }

    /** Convenience for typed operation payloads; null is transmitted as JSON null. */
    public static JSONObject operation(String kind, Object... fields) {
        try {
            JSONObject object = new JSONObject().put("kind", kind);
            for (int i = 0; i < fields.length; i += 2)
                object.put((String) fields[i], fields[i + 1] == null ? JSONObject.NULL : fields[i + 1]);
            return object;
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }

    private JSONObject call(JSONObject request) throws IOException, JSONException {
        JSONObject response = rpc.request(request);
        if (response == null || !(response.opt("success") instanceof Boolean))
            throw new IOException("Invalid huge-page response");
        if (!response.getBoolean("success")) throw new VmConfigClient.RemoteFailure(response);
        return response;
    }

    public JSONObject snapshot() throws IOException, JSONException {
        JSONObject caps = call(new JSONObject().put("command", "capabilities"));
        if (!CAPABILITY.equals(caps.optString("hugepages")))
            throw new Blocked("Daemon does not support " + CAPABILITY, false, null);
        JSONObject snapshot = call(new JSONObject().put("command", "hugepage_snapshot")).getJSONObject("snapshot");
        if (!CAPABILITY.equals(snapshot.optString("capability")) || snapshot.optString("fingerprint").isEmpty())
            throw new IOException("Invalid huge-page snapshot");
        return snapshot;
    }

    /** Exactly one submission. The durable business UUID survives transport timeout and app restart. */
    public synchronized Receipt execute(JSONObject operation) {
        if (pending != null) throw new Blocked("Pending request: " + pending, true, null);
        if (journal == null) throw new Blocked("Huge-page request journal is unavailable", false, null);
        final JSONObject before;
        try { before = snapshot(); }
        catch (Blocked e) { throw e; }
        catch (Exception e) { throw new Blocked(e.getMessage(), false, e); }
        String id = UUID.randomUUID().toString();
        if (!journal.save(id))
            throw new Blocked("Could not save huge-page request identity", false, null);
        pending = id;
        final JSONObject response;
        try {
            response = call(new JSONObject().put("command", "hugepage_operation_start")
                .put("requestId", id).put("expected", new JSONObject().put("fingerprint", before.getString("fingerprint")))
                .put("operation", operation));
        } catch (VmConfigClient.RemoteFailure e) {
            // These admission failures are guaranteed to precede mutation. Unknown codes stay locked.
            if ("INVALID_ARGUMENT".equals(e.code) || "CONFLICT".equals(e.code)
                || "RESOURCE_EXHAUSTED".equals(e.code) || "UNAVAILABLE".equals(e.code)
                || "UNAUTHENTICATED".equals(e.code) || "PERMISSION_DENIED".equals(e.code)) {
                clearPending();
                throw new Blocked(e.getMessage(), false, e);
            }
            throw new Blocked(e.getMessage(), true, e);
        } catch (Blocked e) { throw e; }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Blocked(e.getMessage(), true, e);
        }
        try { return await(taskFrom(response)); }
        catch (Blocked e) { throw e; }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Blocked(e.getMessage(), true, e);
        }
    }

    /** Queries the original UUID only; never retransmits start or invents a replacement request. */
    public synchronized Receipt queryPending() {
        if (pending == null) return null;
        try { return await(status()); }
        catch (Blocked e) { throw e; }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Blocked(e.getMessage(), true, e);
        }
    }

    private JSONObject status() throws IOException, JSONException {
        return taskFrom(call(new JSONObject().put("command", "hugepage_operation_status")
            .put("requestId", pending)));
    }

    private JSONObject taskFrom(JSONObject response) throws IOException, JSONException {
        JSONObject task = response.getJSONObject("task");
        String epoch = task.getString("epoch"), id = task.getString("task_id");
        if (!pending.equals(task.optString("request_id"))
            || !"hugepage_operation".equals(task.optString("kind"))
            || !"hugepages".equals(task.optString("resource"))
            || epoch.isEmpty() || id.isEmpty()
            || !epoch.equals(response.optString("epoch")) || !id.equals(response.optString("task_id"))
            || (taskEpoch != null && (!taskEpoch.equals(epoch) || !taskId.equals(id))))
            throw new IOException("Huge-page task identity mismatch");
        if (taskEpoch == null) {
            if (!journal.save(new JSONObject().put("requestId", pending).put("epoch", epoch)
                .put("taskId", id).toString())) throw new IOException("Could not save huge-page task identity");
            taskEpoch = epoch;
            taskId = id;
        }
        return task;
    }

    private void restorePending(String encoded) {
        if (encoded == null) return;
        try {
            JSONObject value = new JSONObject(encoded);
            pending = value.getString("requestId");
            taskEpoch = value.getString("epoch");
            taskId = value.getString("taskId");
        } catch (JSONException ignored) {
            // The initial durable admission record contains only the business UUID.
            pending = encoded;
            taskEpoch = null;
            taskId = null;
        }
    }

    private static boolean terminal(JSONObject task) {
        String state = task.optString("state");
        return "succeeded".equals(state) || "failed".equals(state) || "cancelled".equals(state);
    }

    private static JSONObject result(JSONObject task) throws IOException {
        JSONObject result = task.optJSONObject("result");
        for (String key : new String[]{"saved", "applied", "degraded", "uncertain"})
            if (result == null || !(result.opt(key) instanceof Boolean))
                throw new IOException("Missing or invalid huge-page receipt: " + key);
        return result;
    }

    private static boolean needsAcknowledgment(JSONObject task, JSONObject result) {
        JSONObject error = task.optJSONObject("error");
        return result.optBoolean("uncertain")
            || (error != null && "COMMIT_UNCERTAIN".equals(error.optString("code")))
            || (!"succeeded".equals(task.optString("state"))
                && (result.optBoolean("saved") || result.optBoolean("applied")));
    }

    /** Fresh read for explicit user review, allowed only after a validated terminal partial result. */
    public synchronized JSONObject readTerminalRecovery() {
        try {
            requireTerminalRecovery();
            return snapshot();
        } catch (Blocked e) { throw e; }
        catch (Exception e) { throw new Blocked(e.getMessage(), true, e); }
    }

    /** Accepts the reviewed state locally. Does not retry or alter native state. */
    public synchronized void acknowledgeTerminalRecovery(String reviewedFingerprint) {
        try {
            requireTerminalRecovery();
            if (!snapshot().getString("fingerprint").equals(reviewedFingerprint))
                throw new Blocked("Huge-page state changed; review the current state again", true, true, null);
            clearPending();
        } catch (Blocked e) { throw e; }
        catch (Exception e) { throw new Blocked(e.getMessage(), true, e); }
    }

    private void requireTerminalRecovery() throws IOException, JSONException {
        if (pending == null) throw new IOException("No pending huge-page operation");
        JSONObject task = status();
        if (!terminal(task) || !needsAcknowledgment(task, result(task)))
            throw new IOException("The original operation does not have a confirmed partial terminal result");
    }

    private Receipt await(JSONObject task) throws IOException, JSONException, InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        for (;;) {
            String state = task.optString("state");
            if (terminal(task)) {
                JSONObject result = result(task);
                if (needsAcknowledgment(task, result))
                    throw new Blocked("Huge-page result requires reconciliation: " + pending, true, true, null);
                Receipt receipt = new Receipt(task);
                clearPending();
                return receipt;
            }
            if (!("queued".equals(state) || "running".equals(state) || "cancelling".equals(state)))
                throw new IOException("Invalid huge-page task state: " + state);
            if (System.nanoTime() >= deadline)
                throw new IOException("Huge-page task still pending: " + pending);
            Thread.sleep(250);
            task = status();
        }
    }

    private void clearPending() {
        if (!journal.clear())
            throw new Blocked("Could not clear huge-page request journal", true, null);
        pending = null;
        taskEpoch = null;
        taskId = null;
    }
}
