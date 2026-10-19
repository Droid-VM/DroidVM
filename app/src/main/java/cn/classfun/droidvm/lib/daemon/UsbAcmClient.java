// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import cn.classfun.droidvm.daemon.vm.UsbAcmPool;

/** Worker-only ACM owner client. Reads never repair the USB gadget. */
public final class UsbAcmClient {
    public static final String CAPABILITY = "usb-acm-v1";
    private static final UsbAcmClient INSTANCE = new UsbAcmClient(DaemonConnection.getInstance()::request);
    interface Store extends HugePageOperationClient.PendingStore {
        JSONObject legacy() throws JSONException;
        boolean mirror(Snapshot snapshot);
    }
    private final VmConfigClient.Rpc rpc;
    private Store store;
    private volatile String pending;
    private JSONObject admission;
    private String taskEpoch, taskId;
    private UsbAcmClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }
    UsbAcmClient(VmConfigClient.Rpc rpc, Store store) { this.rpc = rpc; this.store = store; restore(store.load()); }
    public static UsbAcmClient getInstance() { return INSTANCE; }
    public static void initialize(Context context) {
        synchronized (INSTANCE) {
            if (INSTANCE.store != null) return;
            var app = context.getApplicationContext();
            var preferences = app.getSharedPreferences("droidvm_prefs", Context.MODE_PRIVATE);
            var journal = app.getSharedPreferences("usb_acm_operations", Context.MODE_PRIVATE);
            INSTANCE.store = new Store() {
                public String load() { return journal.getString("pending", null); }
                public boolean save(String value) { return journal.edit().putString("pending", value).commit(); }
                public boolean clear() { return journal.edit().remove("pending").commit(); }
                public JSONObject legacy() throws JSONException {
                    return new JSONObject(preferences.getAll())
                        .put(UsbAcmPool.KEY_USB_ACM_ENABLE, preferences.getBoolean(UsbAcmPool.KEY_USB_ACM_ENABLE, UsbAcmPool.DEFAULT_ENABLE))
                        .put(UsbAcmPool.KEY_USB_ACM_PORTS, preferences.getInt(UsbAcmPool.KEY_USB_ACM_PORTS, UsbAcmPool.DEFAULT_PORTS));
                }
                public boolean mirror(Snapshot snapshot) {
                    return preferences.edit().putBoolean(UsbAcmPool.KEY_USB_ACM_ENABLE, snapshot.enabled)
                        .putInt(UsbAcmPool.KEY_USB_ACM_PORTS, snapshot.ports).commit();
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
    public static final class Owner {
        public final int slot;
        public final String vmId;
        Owner(JSONObject value) throws IOException, JSONException {
            slot = integer(value, "slot", 0, 5); vmId = token(value.getString("vmId"));
        }
    }
    public static final class Live {
        public final boolean available, applied;
        public final Boolean udcBound;
        public final Integer ports;
        public final List<Owner> owners;
        public final String reason;
        Live(JSONObject value) throws IOException, JSONException {
            available = bool(value, "available"); applied = bool(value, "applied");
            if (!value.has("udcBound") || !value.has("ports")) throw new IOException("Incomplete ACM observation");
            udcBound = value.isNull("udcBound") ? null : bool(value, "udcBound");
            ports = value.isNull("ports") ? null : integer(value, "ports", 0, 6);
            if (!value.has("reason") || (!value.isNull("reason") && !(value.opt("reason") instanceof String))) throw new IOException("Invalid ACM observation reason");
            reason = value.isNull("reason") ? null : token(value.getString("reason"));
            var rows = value.getJSONArray("owners"); if (rows.length() > 6) throw new IOException("Invalid ACM owners");
            var list = new ArrayList<Owner>(); boolean[] seen = new boolean[6];
            for (int i = 0; i < rows.length(); i++) {
                var owner = new Owner(rows.getJSONObject(i));
                if (seen[owner.slot]) throw new IOException("Duplicate ACM owner slot");
                seen[owner.slot] = true; list.add(owner);
            }
            owners = Collections.unmodifiableList(list);
        }
    }
    public static final class Snapshot {
        public final String revision;
        public final boolean enabled, initialized, persistenceUncertain, writable;
        public final int ports;
        public final Live live;
        Snapshot(JSONObject value) throws IOException, JSONException {
            if (!CAPABILITY.equals(value.optString("capability"))) throw new IOException("Invalid ACM capability");
            revision = revision(value.getJSONObject("expected"));
            JSONObject desired = desired(value.getJSONObject("desired"));
            enabled = desired.getBoolean("enabled"); ports = desired.getInt("ports");
            initialized = bool(value, "initialized"); persistenceUncertain = bool(value, "persistenceUncertain");
            live = new Live(value.getJSONObject("live"));
            writable = initialized && !persistenceUncertain && !"application-outcome-unknown".equals(live.reason)
                && !"persistence-outcome-unknown".equals(live.reason);
        }
    }
    public static final class Receipt {
        public final boolean succeeded, saved, applied;
        public final String errorCode;
        Receipt(JSONObject task, JSONObject result) {
            succeeded = "succeeded".equals(task.optString("state")); saved = result.optBoolean("saved"); applied = result.optBoolean("applied");
            var error = task.optJSONObject("error"); errorCode = error == null ? "" : error.optString("code", "UNKNOWN");
        }
    }
    private static boolean bool(JSONObject value, String key) throws IOException {
        if (!(value.opt(key) instanceof Boolean)) throw new IOException("Invalid ACM boolean"); return (boolean) value.opt(key);
    }
    private static int integer(JSONObject value, String key, int minimum, int maximum) throws IOException {
        Object number = value.opt(key);
        if (!(number instanceof Integer || number instanceof Long)) throw new IOException("Invalid ACM integer");
        long result = ((Number) number).longValue();
        if (result < minimum || result > maximum) throw new IOException("ACM integer out of range"); return (int) result;
    }
    private static String token(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IOException("Invalid ACM identity"); return value;
    }
    private static String revision(JSONObject value) throws IOException, JSONException {
        if (value.length() != 1) throw new IOException("Invalid ACM revision"); return token(value.getString("revision"));
    }
    private static JSONObject desired(JSONObject value) throws IOException, JSONException {
        if (value.length() != 2) throw new IOException("Invalid ACM settings"); bool(value, "enabled"); integer(value, "ports", 1, 6);
        return new JSONObject(value.toString());
    }
    private JSONObject call(String command, JSONObject input) throws IOException, JSONException {
        return DaemonOperationSupport.call(rpc, input.put("command", command));
    }
    static boolean supported(VmConfigClient.Rpc rpc) throws IOException, JSONException {
        var caps = DaemonOperationSupport.call(rpc, new JSONObject().put("command", "capabilities"));
        if (!caps.has("usb_acm")) return false;
        if (!CAPABILITY.equals(caps.optString("usb_acm"))) throw new IOException("Unsupported ACM API"); return true;
    }
    public boolean hasPending() { return pending != null; }
    public synchronized Snapshot refresh() throws IOException, JSONException {
        if (!supported(rpc)) throw new Blocked("UNSUPPORTED", false, true, null);
        return accept(call("usb_acm_snapshot", new JSONObject()).getJSONObject("snapshot"));
    }
    synchronized Snapshot accept(JSONObject value) throws IOException, JSONException {
        Snapshot snapshot = new Snapshot(value);
        if (store == null) throw new IOException("ACM local mirror unavailable");
        if (snapshot.writable && !store.mirror(snapshot)) throw new IOException("Could not refresh ACM mirror");
        return snapshot;
    }
    /** No call into HostPreferencesClient's instance lock: synchronization is Host -> ACM only. */
    public synchronized Snapshot synchronizeLegacy() throws IOException, JSONException {
        Snapshot before = refresh();
        if (before.initialized || before.persistenceUncertain || hasPending()) return before;
        JSONObject config = store.legacy();
        HostPreferencesClient.stripInitialized(rpc, config);
        DiskDefaultsClient.stripInitialized(rpc, config);
        try { return accept(call("set_app_config", new JSONObject().put("config", config)).getJSONObject("usb_acm")); }
        catch (IOException | JSONException error) {
            Snapshot observed = refresh(); if (!observed.initialized) throw error; return observed;
        }
    }
    /** Used before another domain's first legacy import; no gadget writes or instance locking. */
    static void stripInitialized(VmConfigClient.Rpc rpc, JSONObject config) throws IOException, JSONException {
        if (!supported(rpc)) return;
        var snapshot = new Snapshot(DaemonOperationSupport.call(rpc, new JSONObject().put("command", "usb_acm_snapshot")).getJSONObject("snapshot"));
        if (snapshot.initialized) strip(config);
    }
    static void strip(JSONObject config) { config.remove(UsbAcmPool.KEY_USB_ACM_ENABLE); config.remove(UsbAcmPool.KEY_USB_ACM_PORTS); }
    public synchronized Receipt configure(Snapshot baseline, boolean enabled, int ports) {
        try { return submit(baseline, new JSONObject().put("kind", "configure").put("enabled", enabled).put("ports", ports)); }
        catch (JSONException error) { throw new Blocked("INVALID_ARGUMENT", false, false, error); }
    }
    public synchronized Receipt apply(Snapshot baseline) {
        try { return submit(baseline, new JSONObject().put("kind", "apply")); }
        catch (JSONException error) { throw new Blocked("INVALID_ARGUMENT", false, false, error); }
    }
    private Receipt submit(Snapshot baseline, JSONObject operation) {
        if (pending != null) throw new Blocked("PENDING", true, false, null);
        if (store == null || baseline == null || !baseline.writable) throw new Blocked("UNAVAILABLE", false, false, null);
        try {
            validateOperation(operation);
            if (!supported(rpc)) throw new Blocked("UNSUPPORTED", false, true, null);
            JSONObject input = new JSONObject().put("requestId", UUID.randomUUID().toString())
                .put("expected", new JSONObject().put("revision", baseline.revision)).put("operation", operation);
            if (!store.save(input.toString())) throw new IOException("Could not save ACM request identity");
            admission = input; pending = input.getString("requestId");
            JSONObject response;
            try { response = call("usb_acm_operation_start", new JSONObject(input.toString())); }
            catch (VmConfigClient.RemoteFailure error) {
                if (DaemonOperationSupport.rejectedBeforeMutation(error.code)) { clear(); throw new Blocked(error.code, false, false, error); }
                throw error;
            }
            return await(taskFrom(response));
        } catch (Blocked error) { throw error; }
        catch (Exception error) { if (error instanceof InterruptedException) Thread.currentThread().interrupt(); throw new Blocked("REQUEST_FAILED", pending != null, false, error); }
    }
    private static void validateOperation(JSONObject operation) throws IOException, JSONException {
        if ("configure".equals(operation.optString("kind")) && operation.length() == 3) { bool(operation, "enabled"); integer(operation, "ports", 1, 6); }
        else if (!("apply".equals(operation.optString("kind")) && operation.length() == 1)) throw new IOException("Invalid ACM operation");
    }
    public synchronized Receipt queryPending() {
        if (pending == null) return null;
        try { return await(status()); }
        catch (Blocked error) { throw error; }
        catch (Exception error) { if (error instanceof InterruptedException) Thread.currentThread().interrupt(); throw new Blocked("PENDING", true, false, error); }
    }
    private JSONObject status() throws IOException, JSONException {
        if (admission == null) throw new IOException("Invalid pending ACM identity");
        return taskFrom(call("usb_acm_operation_status", new JSONObject().put("requestId", pending)));
    }
    private JSONObject taskFrom(JSONObject response) throws IOException, JSONException {
        JSONObject task = DaemonOperationSupport.task(response, pending, "usb_acm", "usb-acm", taskEpoch, taskId);
        if (taskEpoch == null) {
            String epoch = task.getString("epoch"), id = task.getString("task_id");
            if (!store.save(new JSONObject(admission.toString()).put("epoch", epoch).put("taskId", id).toString())) throw new IOException("Could not save ACM task identity");
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
                boolean saved = bool(result, "saved"), applied = bool(result, "applied"), uncertain = bool(result, "uncertain");
                for (String key : new String[]{"expected", "desired", "revision", "live"}) if (!result.has(key)) throw new IOException("Incomplete ACM receipt");
                boolean cancelled = "cancelled".equals(state) && !saved && !applied && !uncertain
                    && result.isNull("expected") && result.isNull("desired") && result.isNull("revision") && result.isNull("live");
                if (!cancelled) {
                    if (!revision(admission.getJSONObject("expected")).equals(revision(result.getJSONObject("expected")))) throw new IOException("ACM receipt baseline mismatch");
                    JSONObject actual = desired(result.getJSONObject("desired")); Live live = new Live(result.getJSONObject("live"));
                    if (applied && !live.applied) throw new IOException("Contradictory ACM application result");
                    if (saved) {
                        token(result.getString("revision"));
                        JSONObject operation = admission.getJSONObject("operation");
                        if ("apply".equals(operation.getString("kind")) && !result.getString("revision").equals(revision(admission.getJSONObject("expected"))))
                            throw new IOException("ACM apply revision mismatch");
                        if ("configure".equals(operation.getString("kind")) && (actual.getBoolean("enabled") != operation.getBoolean("enabled") || actual.getInt("ports") != operation.getInt("ports")))
                            throw new IOException("ACM saved values mismatch");
                    }
                    if ("succeeded".equals(state) && (!saved || !applied)) throw new IOException("Incomplete successful ACM result");
                }
                var error = task.optJSONObject("error");
                if (uncertain || (error != null && "COMMIT_UNCERTAIN".equals(error.optString("code")))) throw new Blocked("PENDING", true, false, null);
                Receipt receipt = new Receipt(task, result); clear(); return receipt;
            }
            if (!("queued".equals(state) || "running".equals(state) || "cancelling".equals(state))) throw new IOException("Invalid ACM task state");
            if (System.nanoTime() >= deadline) throw new IOException("ACM task still pending");
            Thread.sleep(250); task = status();
        }
    }
    private void restore(String encoded) {
        if (encoded == null) return; pending = encoded;
        try {
            JSONObject record = new JSONObject(encoded); String id = record.getString("requestId"); UUID.fromString(id);
            revision(record.getJSONObject("expected")); validateOperation(record.getJSONObject("operation"));
            admission = new JSONObject().put("requestId", id).put("expected", record.getJSONObject("expected")).put("operation", record.getJSONObject("operation")); pending = id;
            if (record.has("epoch") || record.has("taskId")) { taskEpoch = token(record.getString("epoch")); taskId = token(record.getString("taskId")); }
        } catch (Exception ignored) { admission = null; }
    }
    private void clear() {
        if (!store.clear()) throw new Blocked("JOURNAL_UNAVAILABLE", true, false, null);
        pending = null; admission = null; taskEpoch = null; taskId = null;
    }
}
