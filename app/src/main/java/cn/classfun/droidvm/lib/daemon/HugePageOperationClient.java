// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import android.content.Context;
import android.util.Log;
import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;

/** Worker-thread client. Only the native owner may mutate the huge-page module or settings. */
public final class HugePageOperationClient {
    public static final String CAPABILITY = "coordinated-hugepages-v1";
    /** Canonical audit line for every settlement: reconcile: op &lt;id&gt; resolved as &lt;word&gt;. */
    private static final String TAG = "HugePageReconcile";
    private static final HugePageOperationClient INSTANCE =
        new HugePageOperationClient(DaemonConnection.getInstance()::request);
    private final VmConfigClient.Rpc rpc;
    interface PendingStore {
        String load();
        boolean save(String requestId);
        boolean clear();
        /**
         * The recorded intent (a JSON operation object) that produced the pending
         * request. Older journals predate it and return null; defaults keep any
         * existing store implementation source-compatible.
         */
        default String loadIntent() { return null; }
        default boolean saveIntent(String intent) { return true; }
        default boolean clearIntent() { return true; }
    }
    private PendingStore journal;
    private volatile String pending;
    /** Operation that produced {@link #pending}; null when the journal predates intents. */
    private JSONObject intent;
    private String taskEpoch, taskId;

    public HugePageOperationClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }
    HugePageOperationClient(VmConfigClient.Rpc rpc, PendingStore journal) {
        this.rpc = rpc;
        this.journal = journal;
        restorePending(journal.load());
    }
    public static HugePageOperationClient getInstance() { return INSTANCE; }
    public boolean hasPending() { return pending != null; }
    /** Business UUID of the pending request, or null when there is none. */
    @Nullable public String pendingId() { return pending; }
    public static void initialize(Context context) {
        synchronized (INSTANCE) {
            if (INSTANCE.journal != null) return;
            var preferences = context.getApplicationContext()
                .getSharedPreferences("hugepage_operations", Context.MODE_PRIVATE);
            INSTANCE.journal = new PendingStore() {
                public String load() { return preferences.getString("requestId", null); }
                public boolean save(String id) { return preferences.edit().putString("requestId", id).commit(); }
                public boolean clear() { return preferences.edit().remove("requestId").commit(); }
                public String loadIntent() { return preferences.getString("operation", null); }
                public boolean saveIntent(String value) {
                    return preferences.edit().putString("operation", value).commit();
                }
                public boolean clearIntent() { return preferences.edit().remove("operation").commit(); }
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
        // Record what was asked for, not only that something was asked for: if the
        // daemon later loses the task record, this is the only thing left that can be
        // compared with the module's real state (see reconcilePending).
        intent = operation;
        journal.saveIntent(operation.toString());
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
        // Oldest journals store the bare business UUID; newer ones a JSON envelope.
        pending = encoded;
        try {
            JSONObject value = new JSONObject(encoded);
            pending = value.getString("requestId");
            taskEpoch = value.has("epoch") ? value.getString("epoch") : null;
            taskId = value.has("taskId") ? value.getString("taskId") : null;
        } catch (JSONException ignored) {
            // The initial durable admission record contains only the business UUID.
            taskEpoch = null;
            taskId = null;
        }
        if (journal != null) {
            String recorded = journal.loadIntent();
            if (recorded != null) try {
                intent = new JSONObject(recorded);
            } catch (JSONException ignored) {
                intent = null;
            }
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

    /* ====================================================================== */
    /*  Reconciliation: settle a pending request from the module's own state  */
    /* ====================================================================== */

    /** How a pending request was settled against the module's real state. */
    public enum Resolution { DONE, FAILED, RESUMED }

    /**
     * Result of {@link #reconcilePending()}. A null {@code resolution} means the
     * request is still genuinely unresolved (daemon unreachable, task still running,
     * or nothing comparable was recorded), so the journal is deliberately kept.
     */
    public static final class Reconcile {
        public final String requestId;
        @Nullable public final Resolution resolution;
        public final String detail;

        Reconcile(String requestId, @Nullable Resolution resolution, String detail) {
            this.requestId = requestId;
            this.resolution = resolution;
            this.detail = detail;
        }

        public boolean settled() { return resolution != null; }

        @Override public String toString() {
            return resolution == null ? "unresolved: " + detail : word(resolution) + ": " + detail;
        }
    }

    private static String word(Resolution resolution) {
        return resolution == Resolution.DONE ? "done"
            : resolution == Resolution.FAILED ? "failed" : "resumed";
    }

    private static void audit(String requestId, String word, String detail) {
        Log.i(TAG, "reconcile: op " + requestId + " resolved as " + word + " (" + detail + ")");
    }

    /**
     * Settle a pending request by comparing the recorded intent with the module's
     * real state. Never retransmits the request and never writes native state: it
     * only decides whether the local block may be lifted, so a lost daemon task
     * record can no longer block huge-page editing forever.
     *
     * <p>Emits exactly one audit line per settlement:
     * {@code reconcile: op <id> resolved as <done|failed|resumed>}.
     */
    @Nullable public synchronized Reconcile reconcilePending() {
        if (pending == null) return null;
        if (journal == null) throw new Blocked("Huge-page request journal is unavailable", false, null);
        final String id = pending;
        final JSONObject task;
        try {
            task = status();
        } catch (VmConfigClient.RemoteFailure failure) {
            // Only a definitively unretained record falls back to the module state.
            // Any other refusal (auth, permission, conflict) must keep the block.
            if (!"TASK_NOT_FOUND".equals(failure.code) && !"NOT_FOUND".equals(failure.code))
                return new Reconcile(id, null, "status refused: " + failure.code);
            return settleFromState(id, "the daemon no longer retains the task record");
        } catch (Blocked failure) {
            throw failure;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Reconcile(id, null, "status unavailable: " + e.getMessage());
        }
        if (!terminal(task))
            return new Reconcile(id, null, "the original task is still " + task.optString("state"));
        final JSONObject result;
        try {
            result = result(task);
        } catch (IOException e) {
            return new Reconcile(id, null, e.getMessage());
        }
        if (!needsAcknowledgment(task, result)) {
            clearPending();
            audit(id, "done", "the daemon returned a confirmed terminal receipt");
            return new Reconcile(id, Resolution.DONE, "the original task has a confirmed result");
        }
        return settleFromState(id, "the original task ended with a partial receipt");
    }

    /** Compare the recorded intent with the module's live/saved state and settle. */
    private Reconcile settleFromState(String id, String why) {
        final JSONObject snapshot;
        try {
            snapshot = snapshot();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Reconcile(id, null, why + "; module state unreadable: " + e.getMessage());
        }
        final Boolean applied = matches(intent, snapshot);
        if (applied == null)
            return new Reconcile(id, null, why + "; no comparable intent was recorded");
        final boolean busy = !idle(snapshot);
        if (!applied && busy)
            // Never claim an outcome while the module can still change on its own.
            return new Reconcile(id, null, why + "; the module is still working");
        final Resolution resolution = applied ? Resolution.DONE : Resolution.RESUMED;
        final String detail = applied
            ? "the module already reflects the recorded intent"
            : "the module does not reflect the intent and is idle, so a new operation is safe";
        clearPending();
        audit(id, word(resolution), detail + "; " + why);
        return new Reconcile(id, resolution, detail);
    }

    /**
     * Escape hatch: drop the local pending marker without a native comparison.
     * Never retries and never writes native state; the caller must have shown the
     * observed module state first. Logged so a stuck request stays auditable.
     */
    public synchronized void forceClearPending() {
        if (journal == null) throw new Blocked("Huge-page request journal is unavailable", false, null);
        final String id = pending;
        if (id == null) {
            journal.clear();
            journal.clearIntent();
            intent = null;
            return;
        }
        Log.w(TAG, "reconcile: op " + id + " force-cleared by the user (no native comparison)");
        clearPending();
    }

    /** True when the module reports no acquire worker in flight. */
    private static boolean idle(@Nullable JSONObject snapshot) {
        JSONObject stats = snapshot == null ? null : snapshot.optJSONObject("stats");
        Long active = jsonLong(stats, "acquire_active");
        return active == null || active == 0;
    }

    /**
     * The module sizes the pool in whole pageblock units and may round a requested
     * target down (observed: 4505 requested, 4504 built). A target within this slack
     * is a completed resize, not a different one -- matching the daemon's own
     * tolerance in {@code HugepageService::Impl::write}.
     */
    private static final long POOL_ROUNDING_SLACK = 8;

    /**
     * Does the module's live/saved state already satisfy {@code recorded}?
     * Null means this operation leaves nothing durable to compare, so the request
     * must stay pending (or be cleared explicitly by the user).
     */
    @Nullable private static Boolean matches(@Nullable JSONObject recorded, @Nullable JSONObject snapshot) {
        if (recorded == null || snapshot == null) return null;
        JSONObject controls = snapshot.optJSONObject("controls");
        JSONObject settings = snapshot.optJSONObject("settings");
        JSONObject stats = snapshot.optJSONObject("stats");
        final String kind = recorded.optString("kind");
        switch (kind) {
            case "targets": {
                Long pages = number(recorded, "poolPages");
                if (pages == null) return null;
                return pages.equals(jsonLong(controls, "pool_want"))
                    || pages.equals(propLong(settings, "pool_want", "pool_target"))
                    || withinRounding(pages, jsonLong(controls, "pool_want"))
                    || withinRounding(pages, propLong(settings, "pool_want", "pool_target"));
            }
            case "cma":
            case "cma-target":
            case "cma-save": {
                Long pages = number(recorded, "pages");
                if (pages == null) return null;
                if ("cma".equals(kind) && !recorded.optBoolean("enabled", true)) pages = 0L;
                return pages.equals(jsonLong(controls, "pool_want_with_cma"))
                    || pages.equals(propLong(settings, "pool_want_with_cma"));
            }
            case "acquire": {
                Long want = jsonLong(stats, "pool_want");
                Long avail = jsonLong(stats, "pool_avail");
                return want != null && avail != null && avail >= want;
            }
            case "stop-acquire": {
                Long active = jsonLong(stats, "acquire_active");
                return active != null && active == 0;
            }
            case "runtime":
                return recorded.optBoolean("enabled")
                    ? snapshot.optBoolean("loaded")
                    : !snapshot.optBoolean("loaded") || lessOrEqual(jsonLong(controls, "pool_want"), 1);
            case "unload":
                return !snapshot.optBoolean("loaded");
            case "boot":
                return recorded.optBoolean("enabled") == snapshot.optBoolean("bootEnabled");
            case "dismiss-crash":
                return !snapshot.optBoolean("hasCrash");
            case "migrate-settings":
                return !snapshot.optBoolean("hasLegacySettings");
            default:
                return null;
        }
    }

    private static boolean lessOrEqual(@Nullable Long value, long limit) {
        return value != null && value <= limit;
    }

    /** A pool knob rounded down by at most one pageblock still honours the request. */
    private static boolean withinRounding(@Nullable Long requested, @Nullable Long actual) {
        if (requested == null || actual == null || actual <= 0 || requested <= 0) return false;
        return actual <= requested && requested - actual <= POOL_ROUNDING_SLACK;
    }

    @Nullable private static Long jsonLong(@Nullable JSONObject object, String key) {
        return object == null ? null : number(object, key);
    }

    @Nullable private static Long propLong(@Nullable JSONObject props, String... keys) {
        if (props == null) return null;
        for (String key : keys) {
            Long value = number(props, key);
            if (value != null) return value;
        }
        return null;
    }

    /** Numbers arrive as JSON ints (controls/stats) or as decimal strings (settings.prop). */
    @Nullable private static Long number(@Nullable JSONObject object, String key) {
        if (object == null || !object.has(key)) return null;
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
        journal.clearIntent();
        pending = null;
        taskEpoch = null;
        taskId = null;
        intent = null;
    }
}
