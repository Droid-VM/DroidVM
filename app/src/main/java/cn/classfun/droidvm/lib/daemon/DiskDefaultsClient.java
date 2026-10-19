// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** Worker-only shared disk defaults. Preferences are only a mirror after first migration. */
public final class DiskDefaultsClient {
    public static final String CAPABILITY = "disk-defaults-v1";
    public static final String COMPRESSION = "optimizeCompression", CPUS = "qemuImgCpus";
    public static final String LEGACY_COMPRESSION = "vm_optimize_compression", LEGACY_CPUS = "qemu_img_cpu_affinity";
    private static final DiskDefaultsClient INSTANCE = new DiskDefaultsClient(DaemonConnection.getInstance()::request);
    private final VmConfigClient.Rpc rpc;
    interface Store extends HugePageOperationClient.PendingStore {
        JSONObject legacy() throws JSONException;
        boolean mirror(Snapshot snapshot);
    }
    private Store store;
    private volatile String pending;
    private JSONObject admission;
    private String taskEpoch, taskId;
    private DiskDefaultsClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }
    DiskDefaultsClient(VmConfigClient.Rpc rpc, Store store) { this.rpc = rpc; this.store = store; restore(store.load()); }
    public static DiskDefaultsClient getInstance() { return INSTANCE; }
    public static void initialize(Context context) {
        synchronized (INSTANCE) {
            if (INSTANCE.store != null) return;
            var app = context.getApplicationContext();
            var preferences = app.getSharedPreferences("droidvm_prefs", Context.MODE_PRIVATE);
            var journal = app.getSharedPreferences("disk_defaults_operations", Context.MODE_PRIVATE);
            INSTANCE.store = new Store() {
                public String load() { return journal.getString("pending", null); }
                public boolean save(String value) { return journal.edit().putString("pending", value).commit(); }
                public boolean clear() { return journal.edit().remove("pending").commit(); }
                public JSONObject legacy() throws JSONException {
                    return new JSONObject(preferences.getAll())
                        .put(LEGACY_COMPRESSION, preferences.getString(LEGACY_COMPRESSION, "ask"))
                        .put(LEGACY_CPUS, preferences.getString(LEGACY_CPUS, ""));
                }
                public boolean mirror(Snapshot snapshot) {
                    return preferences.edit().putString(LEGACY_COMPRESSION, snapshot.optimizeCompression)
                        .putString(LEGACY_CPUS, snapshot.cpuCsv()).commit();
                }
            };
            INSTANCE.restore(INSTANCE.store.load());
        }
    }
    public static final class Blocked extends RuntimeException {
        public final String code;
        public final boolean uncertain, unsupported;
        Blocked(String code, boolean uncertain, boolean unsupported, Throwable cause) {
            super(code, cause); this.code = code; this.uncertain = uncertain; this.unsupported = unsupported;
        }
    }
    public static final class Snapshot {
        public final String revision, optimizeCompression;
        public final List<Integer> qemuImgCpus;
        public final boolean initialized, uncertain, writable;
        Snapshot(JSONObject object) throws IOException, JSONException {
            if (!CAPABILITY.equals(object.optString("capability"))) throw new IOException("Invalid disk defaults capability");
            revision = revision(object.getJSONObject("expected"));
            var values = object.getJSONObject("values"); validate(values, false);
            optimizeCompression = values.getString(COMPRESSION);
            qemuImgCpus = Collections.unmodifiableList(cpus(values.getJSONArray(CPUS)));
            initialized = bool(object, "initialized"); uncertain = bool(object, "uncertain");
            writable = initialized && !uncertain;
        }
        public String cpuCsv() {
            var text = new StringBuilder();
            for (int cpu : qemuImgCpus) { if (text.length() > 0) text.append(','); text.append(cpu); }
            return text.toString();
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
    private static boolean bool(JSONObject object, String key) throws IOException {
        if (!(object.opt(key) instanceof Boolean)) throw new IOException("Invalid disk defaults boolean");
        return (Boolean) object.opt(key);
    }
    private static String revision(JSONObject expected) throws IOException, JSONException {
        if (expected.length() != 1 || !(expected.opt("revision") instanceof String)) throw new IOException("Invalid disk defaults revision");
        String revision = expected.getString("revision");
        if (revision.isEmpty() || revision.length() > 128) throw new IOException("Invalid disk defaults revision");
        return revision;
    }
    private static List<Integer> cpus(JSONArray input) throws IOException, JSONException {
        if (input.length() > 1024) throw new IOException("Too many disk CPU cores");
        var result = new TreeSet<Integer>();
        for (int i = 0; i < input.length(); i++) {
            Object value = input.get(i);
            if (!(value instanceof Integer || value instanceof Long)) throw new IOException("Invalid disk CPU core");
            long cpu = ((Number) value).longValue();
            if (cpu < 0 || cpu > 1023 || !result.add((int) cpu)) throw new IOException("Invalid disk CPU core");
        }
        return new ArrayList<>(result);
    }
    private static void validate(JSONObject values, boolean partial) throws IOException, JSONException {
        if (values.length() < 1 || values.length() > 2 || (!partial && values.length() != 2)) throw new IOException("Invalid disk defaults values");
        var keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (COMPRESSION.equals(key)) {
                Object value = values.get(key);
                if (!("ask".equals(value) || "none".equals(value))) throw new IOException("Invalid optimize compression");
            } else if (CPUS.equals(key)) cpus(values.getJSONArray(key));
            else throw new IOException("Unknown disk default");
        }
    }
    public static boolean supported(VmConfigClient.Rpc rpc) throws IOException, JSONException {
        var response = DaemonOperationSupport.call(rpc, new JSONObject().put("command", "capabilities"));
        if (!response.has("disk_defaults")) return false;
        if (!CAPABILITY.equals(response.optString("disk_defaults"))) throw new IOException("Unsupported disk defaults API");
        return true;
    }
    public boolean supported() throws IOException, JSONException { return supported(rpc); }
    private JSONObject call(String command, JSONObject input) throws IOException, JSONException {
        return DaemonOperationSupport.call(rpc, input.put("command", command));
    }
    public boolean hasPending() { return pending != null; }
    public synchronized Snapshot refresh() throws IOException, JSONException {
        if (!supported(rpc)) throw new Blocked("UNSUPPORTED", false, true, null);
        return accept(call("disk_defaults_snapshot", new JSONObject()).getJSONObject("snapshot"));
    }
    synchronized Snapshot accept(JSONObject object) throws IOException, JSONException {
        Snapshot snapshot = new Snapshot(object);
        if (store == null) throw new IOException("Disk defaults mirror unavailable");
        if (snapshot.writable && !store.mirror(snapshot)) throw new IOException("Could not update disk defaults mirror");
        return snapshot;
    }
    /** No instance locks from other owners are acquired here: synchronization order is Host -> ACM/Disk. */
    public synchronized Snapshot synchronizeLegacy() throws IOException, JSONException {
        Snapshot before = refresh();
        if (before.initialized || before.uncertain || hasPending()) return before;
        JSONObject config = store.legacy();
        HostPreferencesClient.stripInitialized(rpc, config);
        UsbAcmClient.stripInitialized(rpc, config);
        try { return accept(call("set_app_config", new JSONObject().put("config", config)).getJSONObject("disk_defaults")); }
        catch (IOException | JSONException error) {
            Snapshot observed = refresh(); if (!observed.initialized) throw error; return observed;
        }
    }
    static void strip(JSONObject config) { config.remove(LEGACY_COMPRESSION); config.remove(LEGACY_CPUS); }
    static void stripInitialized(VmConfigClient.Rpc rpc, JSONObject config) throws IOException, JSONException {
        if (!supported(rpc)) return;
        var snapshot = new Snapshot(DaemonOperationSupport.call(rpc, new JSONObject().put("command", "disk_defaults_snapshot")).getJSONObject("snapshot"));
        if (snapshot.initialized) strip(config);
    }
    /** Resolve before preparing a new disk operation. Never invoke while replaying an existing request. */
    public synchronized Snapshot forNewOperation() throws IOException, JSONException {
        queryPending();
        Snapshot snapshot = synchronizeLegacy();
        if (!snapshot.writable || hasPending()) throw new Blocked("UNAVAILABLE", snapshot.uncertain || hasPending(), false, null);
        return snapshot;
    }
    public synchronized Receipt patch(Snapshot baseline, JSONObject changes) {
        if (pending != null) throw new Blocked("PENDING", true, false, null);
        if (store == null || baseline == null || !baseline.writable) throw new Blocked("UNAVAILABLE", false, false, null);
        try {
            validate(changes, true);
            if (!supported(rpc)) throw new Blocked("UNSUPPORTED", false, true, null);
            var request = new JSONObject().put("requestId", UUID.randomUUID().toString())
                .put("expected", new JSONObject().put("revision", baseline.revision)).put("changes", new JSONObject(changes.toString()));
            if (!store.save(request.toString())) throw new Blocked("JOURNAL_UNAVAILABLE", false, false, null);
            admission = request; pending = request.getString("requestId");
            JSONObject response;
            try { response = call("disk_defaults_operation_start", new JSONObject(request.toString())); }
            catch (VmConfigClient.RemoteFailure error) {
                if (DaemonOperationSupport.rejectedBeforeMutation(error.code)) { clear(); throw new Blocked(error.code, false, false, error); }
                throw error;
            }
            return await(taskFrom(response));
        } catch (Blocked error) { throw error; }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new Blocked("REQUEST_FAILED", pending != null, false, error);
        }
    }
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
        if (admission == null) throw new IOException("Invalid pending disk defaults identity");
        return taskFrom(call("disk_defaults_operation_status", new JSONObject().put("requestId", pending)));
    }
    private JSONObject taskFrom(JSONObject response) throws IOException, JSONException {
        var task = DaemonOperationSupport.task(response, pending, "disk_defaults", "disk-defaults", taskEpoch, taskId);
        String epoch = task.getString("epoch"), id = task.getString("task_id");
        if (taskEpoch == null) {
            if (!store.save(new JSONObject(admission.toString()).put("epoch", epoch).put("taskId", id).toString())) throw new IOException("Could not save disk defaults identity");
            taskEpoch = epoch; taskId = id;
        }
        return task;
    }
    private Receipt await(JSONObject task) throws IOException, JSONException, InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        for (;;) {
            String state = task.optString("state");
            if ("succeeded".equals(state) || "failed".equals(state) || "cancelled".equals(state)) {
                var result = task.getJSONObject("result");
                boolean saved = bool(result, "saved"), uncertain = bool(result, "uncertain");
                if (!result.has("revision") || (!result.isNull("revision") && !(result.opt("revision") instanceof String))
                    || (!saved && !uncertain && !result.isNull("revision"))) throw new IOException("Invalid disk defaults receipt revision");
                boolean queuedCancel = "cancelled".equals(state) && !saved && !uncertain
                    && result.has("expected") && result.isNull("expected") && result.has("values") && result.isNull("values") && result.isNull("revision");
                if (!queuedCancel) {
                    if (!revision(admission.getJSONObject("expected")).equals(revision(result.getJSONObject("expected")))) throw new IOException("Disk defaults baseline mismatch");
                    var actual = result.getJSONObject("values"); validate(actual, false);
                    if (saved) {
                        revision(new JSONObject().put("revision", result.get("revision")));
                        var changes = admission.getJSONObject("changes"); var keys = changes.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            boolean equal = CPUS.equals(key) ? cpus(changes.getJSONArray(key)).equals(cpus(actual.getJSONArray(key))) : changes.get(key).equals(actual.get(key));
                            if (!equal) throw new IOException("Disk defaults receipt values mismatch");
                        }
                    }
                    if ("succeeded".equals(state) && !saved) throw new IOException("Missing successful disk defaults save");
                }
                var error = task.optJSONObject("error");
                if (uncertain || (error != null && "COMMIT_UNCERTAIN".equals(error.optString("code"))) || (!"succeeded".equals(state) && saved)) throw new Blocked("PENDING", true, false, null);
                Receipt receipt = new Receipt(task, result); clear(); return receipt;
            }
            if (!("queued".equals(state) || "running".equals(state) || "cancelling".equals(state))) throw new IOException("Invalid disk defaults task state");
            if (System.nanoTime() >= deadline) throw new IOException("Disk defaults task still pending");
            Thread.sleep(250); task = status();
        }
    }
    private void restore(String encoded) {
        if (encoded == null) return;
        pending = encoded;
        try {
            var record = new JSONObject(encoded);
            String id = record.getString("requestId"); UUID.fromString(id); revision(record.getJSONObject("expected")); validate(record.getJSONObject("changes"), true);
            admission = new JSONObject().put("requestId", id).put("expected", record.getJSONObject("expected")).put("changes", record.getJSONObject("changes"));
            pending = id;
            if (record.has("epoch") || record.has("taskId")) { taskEpoch = record.getString("epoch"); taskId = record.getString("taskId"); }
        } catch (Exception ignored) { admission = null; }
    }
    private void clear() {
        if (!store.clear()) throw new Blocked("JOURNAL_UNAVAILABLE", true, false, null);
        pending = null; admission = null; taskEpoch = null; taskId = null;
    }
}
