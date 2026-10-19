// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;

/** Worker-only, daemon-owned shared preferences. Local preferences are a read mirror. */
public final class HostPreferencesClient {
    public static final String CAPABILITY = "host-preferences-v1";
    public static final String AUTO_RESERVE = "vm_hugepage_auto_reserve";
    public static final String OPTIMIZE_SDCARD = "optimize_sdcard";
    public static final String CLEAR_LOGS = "vm_clear_logs_before_start";
    private static final String[] KEYS = {AUTO_RESERVE, OPTIMIZE_SDCARD, CLEAR_LOGS};
    private static final HostPreferencesClient INSTANCE = new HostPreferencesClient(DaemonConnection.getInstance()::request);
    private final VmConfigClient.Rpc rpc;
    interface Store extends HugePageOperationClient.PendingStore {
        JSONObject legacy() throws JSONException;
        boolean mirror(Snapshot snapshot);
    }
    private Store store;
    private volatile String pending;
    private JSONObject admission;
    private String taskEpoch, taskId;

    private HostPreferencesClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }
    HostPreferencesClient(VmConfigClient.Rpc rpc, Store store) {
        this.rpc = rpc; this.store = store; restore(store.load());
    }
    public static HostPreferencesClient getInstance() { return INSTANCE; }
    public static void initialize(Context context) {
        synchronized (INSTANCE) {
            if (INSTANCE.store != null) return;
            var app = context.getApplicationContext();
            var preferences = app.getSharedPreferences("droidvm_prefs", Context.MODE_PRIVATE);
            var journal = app.getSharedPreferences("host_preferences_operations", Context.MODE_PRIVATE);
            INSTANCE.store = new Store() {
                public String load() { return journal.getString("pending", null); }
                public boolean save(String value) { return journal.edit().putString("pending", value).commit(); }
                public boolean clear() { return journal.edit().remove("pending").commit(); }
                public JSONObject legacy() throws JSONException {
                    // set_app_config still owns the full non-shared App configuration.
                    // Preserve ACM and every other local setting during the first import.
                    JSONObject value = new JSONObject(preferences.getAll());
                    for (String key : KEYS) value.put(key, preferences.getBoolean(key, !CLEAR_LOGS.equals(key)));
                    return value;
                }
                public boolean mirror(Snapshot snapshot) {
                    var edit = preferences.edit();
                    for (String key : KEYS) edit.putBoolean(key, snapshot.value(key));
                    return edit.commit();
                }
            };
            INSTANCE.restore(INSTANCE.store.load());
        }
    }

    public static final class Blocked extends RuntimeException {
        public final boolean uncertain, unsupported;
        public final String code;
        Blocked(String code, boolean uncertain, boolean unsupported, Throwable cause) {
            super(code, cause); this.code = code; this.uncertain = uncertain; this.unsupported = unsupported;
        }
    }
    public static final class Snapshot {
        public final String revision;
        public final boolean initialized, uncertain, writable;
        private final JSONObject values;
        Snapshot(JSONObject value) throws IOException, JSONException {
            if (!CAPABILITY.equals(value.optString("capability"))) throw new IOException("Invalid host preference capability");
            revision = revision(value.getJSONObject("expected"));
            values = values(value.getJSONObject("values"));
            initialized = bool(value, "initialized"); uncertain = bool(value, "uncertain");
            writable = initialized && !uncertain;
        }
        public boolean value(String key) {
            if (!knownKey(key)) throw new IllegalArgumentException("Unknown shared preference");
            return values.optBoolean(key);
        }
    }
    public static final class Receipt {
        public final boolean succeeded, saved;
        public final String errorCode;
        Receipt(JSONObject task, JSONObject result) {
            succeeded = "succeeded".equals(task.optString("state")); saved = result.optBoolean("saved");
            var error = task.optJSONObject("error"); errorCode = error == null ? "" : error.optString("code", "UNKNOWN");
        }
    }

    private static boolean knownKey(String key) {
        for (String candidate : KEYS) if (candidate.equals(key)) return true;
        return false;
    }
    private static boolean bool(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) throw new IOException("Invalid shared preference boolean");
        return (boolean) value;
    }
    private static String revision(JSONObject expected) throws IOException, JSONException {
        if (expected.length() != 1 || !(expected.opt("revision") instanceof String)) throw new IOException("Invalid preference revision");
        String value = expected.getString("revision");
        if (value.isEmpty() || value.length() > 128) throw new IOException("Invalid preference revision");
        return value;
    }
    private static JSONObject values(JSONObject values) throws IOException, JSONException {
        if (values.length() != KEYS.length) throw new IOException("Invalid host preference values");
        for (String key : KEYS) bool(values, key);
        return new JSONObject(values.toString());
    }
    private static JSONObject call(VmConfigClient.Rpc rpc, JSONObject request) throws IOException, JSONException {
        return DaemonOperationSupport.call(rpc, request);
    }
    public static boolean supported(VmConfigClient.Rpc rpc) throws IOException, JSONException {
        JSONObject response = call(rpc, new JSONObject().put("command", "capabilities"));
        if (!response.has("host_preferences")) return false;
        if (!CAPABILITY.equals(response.optString("host_preferences"))) throw new IOException("Unsupported host preference API");
        return true;
    }
    public boolean supported() throws IOException, JSONException { return supported(rpc); }
    private JSONObject call(String command, JSONObject input) throws IOException, JSONException {
        return call(rpc, input.put("command", command));
    }
    public boolean hasPending() { return pending != null; }

    /** No cache may grant write access, and an uncertain native store is never mirrored as saved. */
    public synchronized Snapshot refresh() throws IOException, JSONException {
        if (!supported()) throw new Blocked("UNSUPPORTED", false, true, null);
        Snapshot snapshot = new Snapshot(call("host_preferences_snapshot", new JSONObject()).getJSONObject("snapshot"));
        mirror(snapshot);
        return snapshot;
    }
    private void mirror(Snapshot snapshot) throws IOException {
        if (store == null) throw new IOException("Host preference store is unavailable");
        if (snapshot.writable && !store.mirror(snapshot)) throw new IOException("Could not update host preference mirror");
    }

    /** The daemon atomically accepts legacy values only while its owner is uninitialized. */
    public synchronized Snapshot synchronizeLegacy() throws IOException, JSONException {
        Snapshot before = refresh();
        if (before.initialized || before.uncertain || hasPending()) return before;
        JSONObject response;
        try {
            JSONObject config = store.legacy();
            UsbAcmClient.stripInitialized(rpc, config);
            DiskDefaultsClient.stripInitialized(rpc, config);
            response = call("set_app_config", new JSONObject().put("config", config));
        }
        catch (IOException | JSONException error) {
            // Lost migration receipt is resolved by a read, never by replaying stale preferences.
            Snapshot observed = refresh();
            if (!observed.initialized) throw error;
            return observed;
        }
        Snapshot snapshot = new Snapshot(response.getJSONObject("host_preferences"));
        mirror(snapshot);
        return snapshot;
    }

    /** Serialize migration/mirroring with the remaining local configuration publication. */
    public synchronized void synchronizeAppConfig() throws IOException, JSONException {
        if (store == null) throw new IOException("Host preference store is unavailable");
        // Owners negotiate independently. Failed capability reads never
        // downgrade to publishing stale shared values.
        boolean hostSupported = supported(), acmSupported = UsbAcmClient.supported(rpc), diskSupported = DiskDefaultsClient.supported(rpc);
        Exception sharedFailure = null;
        if (hostSupported) {
            try { synchronizeLegacy(); }
            catch (IOException | JSONException | Blocked error) { sharedFailure = error; }
        }
        // Lock order is Host -> ACM / Disk. Neither client acquires this instance.
        if (acmSupported) {
            try { UsbAcmClient.getInstance().synchronizeLegacy(); }
            catch (IOException | JSONException | UsbAcmClient.Blocked error) { sharedFailure = error; }
        }
        if (diskSupported) {
            try { DiskDefaultsClient.getInstance().synchronizeLegacy(); }
            catch (IOException | JSONException | DiskDefaultsClient.Blocked error) { sharedFailure = error; }
        }
        // Capture local preferences after mirroring, rather than retaining an
        // Activity-thread snapshot while another operation is in flight.
        JSONObject config = store.legacy();
        if (hostSupported) for (String key : KEYS) config.remove(key);
        if (acmSupported) UsbAcmClient.strip(config);
        if (diskSupported) DiskDefaultsClient.strip(config);
        JSONObject response = call("set_app_config", new JSONObject().put("config", config));
        JSONObject authoritative = response.optJSONObject("host_preferences");
        if (hostSupported && authoritative != null) mirror(new Snapshot(authoritative));
        JSONObject acm = response.optJSONObject("usb_acm");
        if (acmSupported && acm != null) UsbAcmClient.getInstance().accept(acm);
        JSONObject disk = response.optJSONObject("disk_defaults");
        if (diskSupported && disk != null) DiskDefaultsClient.getInstance().accept(disk);
        if (sharedFailure != null && authoritative == null && acm == null && disk == null) throw new IOException("Shared host preferences unavailable", sharedFailure);
    }

    /** Other owners must not republish an already imported local mirror; no instance lock. */
    static void stripInitialized(VmConfigClient.Rpc rpc, JSONObject config) throws IOException, JSONException {
        if (!supported(rpc)) return;
        var snapshot = new Snapshot(call(rpc, new JSONObject().put("command", "host_preferences_snapshot")).getJSONObject("snapshot"));
        if (snapshot.initialized) for (String key : KEYS) config.remove(key);
    }

    /** A patch uses the exact screen baseline. It never reads a newer revision and retries. */
    public synchronized Receipt patch(Snapshot baseline, String key, boolean value) {
        if (pending != null) throw new Blocked("PENDING", true, false, null);
        if (store == null || baseline == null || !baseline.writable || !knownKey(key))
            throw new Blocked("UNAVAILABLE", false, false, null);
        try {
            if (!supported()) throw new Blocked("UNSUPPORTED", false, true, null);
            String id = UUID.randomUUID().toString();
            JSONObject request = new JSONObject().put("requestId", id)
                .put("expected", new JSONObject().put("revision", baseline.revision))
                .put("changes", new JSONObject().put(key, value));
            if (!store.save(request.toString())) throw new Blocked("JOURNAL_UNAVAILABLE", false, false, null);
            admission = request; pending = id;
            JSONObject response;
            try { response = call("host_preferences_operation_start", new JSONObject(request.toString())); }
            catch (VmConfigClient.RemoteFailure failure) {
                if (definiteRejection(failure.code)) {
                    clear(); throw new Blocked(failure.code, false, false, failure);
                }
                throw failure;
            }
            return await(taskFrom(response));
        } catch (Blocked error) { throw error; }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Blocked("REQUEST_FAILED", pending != null, false, error);
        }
    }
    private static boolean definiteRejection(String code) {
        return DaemonOperationSupport.rejectedBeforeMutation(code);
    }

    /** Only queries the persisted request identity, even after an app or daemon restart. */
    public synchronized Receipt queryPending() {
        if (pending == null) return null;
        try { return await(status()); }
        catch (Blocked error) { throw error; }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Blocked("PENDING", true, false, error);
        }
    }
    private JSONObject status() throws IOException, JSONException {
        if (admission == null) throw new IOException("Invalid pending preference identity");
        return taskFrom(call("host_preferences_operation_status", new JSONObject().put("requestId", pending)));
    }
    private JSONObject taskFrom(JSONObject response) throws IOException, JSONException {
        JSONObject task = DaemonOperationSupport.task(response, pending, "host_preferences", "host-preferences", taskEpoch, taskId);
        String epoch = task.getString("epoch"), id = task.getString("task_id");
        if (taskEpoch == null) {
            JSONObject record = new JSONObject(admission.toString()).put("epoch", epoch).put("taskId", id);
            if (!store.save(record.toString())) throw new IOException("Could not save preference task identity");
            taskEpoch = epoch; taskId = id;
        }
        return task;
    }
    private Receipt await(JSONObject task) throws IOException, JSONException, InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        for (;;) {
            String state = task.optString("state");
            if ("succeeded".equals(state) || "failed".equals(state) || "cancelled".equals(state)) {
                JSONObject result = task.getJSONObject("result");
                boolean saved = bool(result, "saved"), uncertain = bool(result, "uncertain");
                if (!result.has("revision") || (!result.isNull("revision") && !(result.opt("revision") instanceof String)))
                    throw new IOException("Invalid preference receipt revision");
                if (!saved && !uncertain && !result.isNull("revision")) throw new IOException("Unconfirmed preference installation");
                boolean queuedCancel = "cancelled".equals(state) && !saved && !uncertain
                    && result.has("expected") && result.isNull("expected") && result.has("values") && result.isNull("values")
                    && result.has("revision") && result.isNull("revision");
                if (!queuedCancel) {
                    if (!revision(admission.getJSONObject("expected")).equals(revision(result.getJSONObject("expected"))))
                        throw new IOException("Preference receipt baseline mismatch");
                    JSONObject actual = values(result.getJSONObject("values"));
                    if (saved) {
                        revision(new JSONObject().put("revision", result.get("revision")));
                        JSONObject changes = admission.getJSONObject("changes");
                        var keys = changes.keys();
                        while (keys.hasNext()) { String key = keys.next(); if (!changes.get(key).equals(actual.get(key))) throw new IOException("Preference receipt value mismatch"); }
                    }
                    if ("succeeded".equals(state) && !saved) throw new IOException("Missing successful preference save");
                }
                var error = task.optJSONObject("error");
                if (uncertain || (error != null && "COMMIT_UNCERTAIN".equals(error.optString("code")))
                    || (!"succeeded".equals(state) && saved)) throw new Blocked("PENDING", true, false, null);
                Receipt receipt = new Receipt(task, result); clear(); return receipt;
            }
            if (!("queued".equals(state) || "running".equals(state) || "cancelling".equals(state))) throw new IOException("Invalid preference task state");
            if (System.nanoTime() >= deadline) throw new IOException("Preference task still pending");
            Thread.sleep(250); task = status();
        }
    }
    private void restore(String encoded) {
        if (encoded == null) return;
        pending = encoded; // A corrupt journal blocks writes instead of silently losing an unknown request.
        try {
            JSONObject record = new JSONObject(encoded);
            String id = record.getString("requestId"); UUID.fromString(id); revision(record.getJSONObject("expected"));
            JSONObject changes = record.getJSONObject("changes");
            if (changes.length() < 1 || changes.length() > 3) return;
            var keys = changes.keys();
            while (keys.hasNext()) { String key = keys.next(); if (!knownKey(key)) return; bool(changes, key); }
            admission = new JSONObject().put("requestId", id).put("expected", record.getJSONObject("expected")).put("changes", changes);
            pending = id;
            if (record.has("epoch") || record.has("taskId")) { taskEpoch = record.getString("epoch"); taskId = record.getString("taskId"); }
        } catch (Exception ignored) { admission = null; }
    }
    private void clear() {
        if (!store.clear()) throw new Blocked("JOURNAL_UNAVAILABLE", true, false, null);
        pending = null; admission = null; taskEpoch = null; taskId = null;
    }
}
