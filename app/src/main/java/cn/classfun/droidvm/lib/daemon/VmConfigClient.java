// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Blocking configuration operations: call on a worker, using an authenticated
 * DaemonConnection::request (or DaemonClient::request). Never retries a save with
 * a newer revision. UI owns its edited document until a confirmed result. */
public final class VmConfigClient {
    public static final int MAX_BYTES = 16 << 20;
    private static final int CHUNK_BYTES = 48 << 10;
    public interface Rpc { JSONObject request(JSONObject request) throws IOException, JSONException; }
    private final Rpc rpc;
    public VmConfigClient(Rpc rpc) { this.rpc = rpc; }

    public static final class RemoteFailure extends IOException {
        public final String code;
        RemoteFailure(JSONObject response) {
            super(response.optString("message", "Configuration request failed"));
            JSONObject error = response.optJSONObject("error");
            code = error == null ? "UNKNOWN" : error.optString("code", "UNKNOWN");
        }
    }
    /** A commit response was lost or malformed. Re-read before deciding to retry. */
    public static final class CommitUnknown extends IOException {
        CommitUnknown(Exception cause) { super("Configuration commit result unknown; reload before retrying", cause); }
    }
    /** A start might already have been admitted. Query runtime state; never auto-retry. */
    public static final class StartUnknown extends IOException {
        StartUnknown(Exception cause) { super("VM start result unknown; reload state before retrying", cause); }
    }
    public static class Version {
        public final String revision;
        public final boolean persisted, recovered, durabilityUncertain;
        Version(JSONObject value) throws JSONException, IOException {
            revision = value.getString("revision");
            if (revision.isEmpty() || revision.length() > 128) throw new IOException("Invalid configuration revision");
            persisted = value.getBoolean("persisted");
            recovered = value.getBoolean("recovered");
            durabilityUncertain = value.getBoolean("durability_uncertain");
        }
    }
    public static final class Snapshot extends Version {
        public final String epoch, cursor;
        private final String json;
        Snapshot(JSONObject metadata, String epoch, String cursor, String json) throws JSONException, IOException {
            super(metadata); this.epoch = epoch; this.cursor = cursor; this.json = json;
        }
        /** Each editor gets a private copy, including unknown root/VM fields. */
        public JSONObject document() throws JSONException { return new JSONObject(json); }
    }

    private JSONObject call(String command, JSONObject request) throws IOException, JSONException {
        JSONObject response = rpc.request(request.put("command", command));
        if (response == null || !(response.opt("success") instanceof Boolean))
            throw new IOException("Missing or invalid configuration response");
        if (!response.getBoolean("success")) throw new RemoteFailure(response);
        return response;
    }
    public boolean supported() throws IOException, JSONException {
        JSONObject capabilities = call("capabilities", new JSONObject());
        boolean supported = "base64-json-chunks-v1".equals(capabilities.optString("vm_config_snapshot"))
            && "revisioned-chunks-v1".equals(capabilities.optString("vm_config_upload"))
            && "revisioned-batch-v1".equals(capabilities.optString("vm_config_updates"));
        if (!supported) {
            var keys = capabilities.keys();
            while (keys.hasNext())
                if (keys.next().startsWith("vm_config_"))
                    throw new IOException("Unsupported VM configuration API; local writes are not permitted");
        }
        return supported;
    }
    public Version status() throws IOException, JSONException { return new Version(call("vm_config_status", new JSONObject())); }

    public boolean startSupported() throws IOException, JSONException {
        var capabilities = call("capabilities", new JSONObject());
        if (!capabilities.has("vm_config_start")) return false;
        if (!"revisioned-session-v1".equals(capabilities.optString("vm_config_start")))
            throw new IOException("Unsupported configured VM start API");
        return true;
    }

    /**
     * Uses the caller's observed revision. Never reloads or replaces configuration to start; a
     * reopened, not yet confirmed file is synced by the daemon before it launches anything.
     */
    public void start(Snapshot snapshot, String vmId, JSONObject overrides, String bootEntry, boolean clearLogs)
        throws IOException, JSONException {
        if (vmId == null || vmId.isEmpty() || vmId.length() > 128) throw new IOException("Invalid VM ID");
        boolean found = false;
        JSONArray rows = snapshot.document().getJSONArray("vms");
        for (int i = 0; i < rows.length(); i++)
            if (vmId.equals(rows.getJSONObject(i).getString("id"))) { found = true; break; }
        if (!found) throw new IOException("VM is not in the observed configuration snapshot");
        String boot = bootEntry == null ? "" : bootEntry;
        if (boot.getBytes(StandardCharsets.UTF_8).length > (64 << 10)) throw new IOException("Boot entry exceeds start request limit");
        JSONObject request = new JSONObject().put("revision", snapshot.revision).put("vm_id", vmId)
            .put("boot_entry", boot).put("clear_logs_before_start", clearLogs)
            .put("overrides", overrides == null ? new JSONObject() : new JSONObject(overrides.toString()));
        try {
            JSONObject response = call("vm_start_configured", request);
            if (!vmId.equals(response.getString("vm_id")) || !snapshot.revision.equals(response.getString("revision")))
                throw new IOException("Mismatched configured start response");
        } catch (RemoteFailure failure) {
            if ("INTERNAL".equals(failure.code) || "UNKNOWN".equals(failure.code)) throw new StartUnknown(failure);
            throw failure;
        } catch (IOException | JSONException failure) { throw new StartUnknown(failure); }
    }

    /** One editor's baseline. Conflicts/uncertain commits require an explicit new session,
     * never a silent refresh followed by an overwrite of somebody else's changes. */
    public final class EditSession {
        private String revision;
        private JSONObject baseline;
        private boolean reloadRequired;
        private EditSession(Snapshot snapshot) throws JSONException {
            revision = snapshot.revision;
            baseline = snapshot.document();
        }
        public synchronized JSONObject document() throws JSONException {
            return new JSONObject(baseline.toString());
        }
        public synchronized boolean requiresReload() { return reloadRequired; }
        public synchronized void saveVm(JSONObject config, boolean create) throws IOException, JSONException {
            saveVms(new JSONArray().put(config), create);
        }
        /** Atomically replaces several existing VMs, preserving every other entry. */
        public synchronized void saveVms(JSONArray configs) throws IOException, JSONException {
            saveVms(configs, false);
        }
        /** Import replaces matching IDs and appends new IDs in one conditional commit. */
        public synchronized void mergeVms(JSONArray configs) throws IOException, JSONException {
            saveVms(configs, null);
        }
        // null is the explicit mixed create/replace import mode.
        private void saveVms(JSONArray configs, Boolean create) throws IOException, JSONException {
            if (reloadRequired) throw new IOException("Reload configuration before saving again");
            if (configs.length() == 0) return;
            JSONObject next = document();
            mergeRows(next.getJSONArray("vms"), configs, create);
            commitEdit(next, () -> replace(revision, next));
        }
        public synchronized void deleteVm(String id) throws IOException, JSONException {
            if (reloadRequired) throw new IOException("Reload configuration before deleting again");
            JSONObject next = document();
            JSONArray rows = next.getJSONArray("vms");
            int index = -1;
            for (int i = 0; i < rows.length(); i++)
                if (id.equals(rows.getJSONObject(i).getString("id"))) { index = i; break; }
            if (index < 0) throw new IOException("VM is not in the confirmation snapshot");
            rows.remove(index);
            JSONArray operations = new JSONArray().put(new JSONObject().put("op", "delete").put("id", id));
            commitEdit(next, () -> apply(revision, operations));
        }
        private void commitEdit(JSONObject next, Commit action) throws IOException, JSONException {
            try {
                Version saved = action.run();
                revision = saved.revision;
                baseline = next;
                reloadRequired = saved.durabilityUncertain;
                if (reloadRequired) throw new IOException("Configuration durability is uncertain; reload before retrying");
            } catch (CommitUnknown failure) {
                reloadRequired = true;
                throw failure;
            } catch (RemoteFailure failure) {
                if ("CONFLICT".equals(failure.code) || "COMMIT_UNCERTAIN".equals(failure.code)
                    || "INTERNAL".equals(failure.code) || "UNKNOWN".equals(failure.code)) reloadRequired = true;
                throw failure;
            }
        }
    }
    private interface Commit { Version run() throws IOException, JSONException; }
    /** Shared ID-based import; callers supply a private document and publish only on success. */
    static void mergeRows(JSONArray rows, JSONArray configs, Boolean create) throws IOException, JSONException {
        var positions = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < rows.length(); i++)
            if (positions.put(rows.getJSONObject(i).getString("id"), i) != null)
                throw new IOException("Repeated identity in configuration baseline");
        var targets = new java.util.HashSet<String>();
        for (int i = 0; i < configs.length(); i++) {
            JSONObject config = configs.getJSONObject(i);
            String id = config.getString("id");
            Integer index = positions.get(id);
            if (!targets.add(id)) throw new IOException("Repeated identity in configuration update");
            if (create != null && create == (index != null)) throw new IOException("Configuration no longer matches the editor baseline");
            rows.put(index == null ? rows.length() : index, new JSONObject(config.toString()));
        }
    }
    public EditSession edit() throws IOException, JSONException { return new EditSession(read()); }

    public Snapshot read() throws IOException, JSONException {
        Document document = readDocument("vm_config_snapshot", "vms", MAX_BYTES);
        return new Snapshot(document.metadata, document.epoch, document.cursor, document.json);
    }
    static final class Document {
        final JSONObject metadata;
        final String epoch, cursor, json;
        Document(JSONObject metadata, String epoch, String cursor, String json) {
            this.metadata = metadata; this.epoch = epoch; this.cursor = cursor; this.json = json;
        }
    }
    Document readDocument(String command, String rootKey, int maxBytes) throws IOException, JSONException {
        JSONObject page = call(command, new JSONObject().put("max_bytes", 128 << 10));
        String id = page.getString("snapshot_id");
        try {
            String epoch = page.getString("epoch"), cursor = page.getString("cursor");
            int total = page.getInt("total"), seen = 0, expected = -1;
            if (id.isEmpty() || epoch.isEmpty() || cursor.isEmpty()
                    || total < 1 || total > 1 + (maxBytes + CHUNK_BYTES - 1) / CHUNK_BYTES)
                throw new IOException("Invalid document snapshot row count");
            JSONObject metadata = null;
            ByteArrayOutputStream output = null;
            for (;;) {
                if (!id.equals(page.getString("snapshot_id")) || !epoch.equals(page.getString("epoch"))
                    || !cursor.equals(page.getString("cursor")) || page.getInt("total") != total)
                    throw new IOException("Configuration snapshot changed between pages");
                JSONArray items = page.getJSONArray("items");
                if (items.length() == 0 || items.length() > total - seen) throw new IOException("Invalid snapshot page length");
                for (int i = 0; i < items.length(); ++i) {
                    JSONObject row = items.getJSONObject(i);
                    if (seen++ == 0) {
                        metadata = row;
                        if (!"metadata".equals(row.getString("type")) || !"base64-json".equals(row.getString("encoding")))
                            throw new IOException("Unsupported snapshot encoding");
                        long size = row.getLong("bytes");
                        if (size <= 0 || size > maxBytes) throw new IOException("Invalid configuration size");
                        expected = (int) size;
                        output = new ByteArrayOutputStream(expected);
                    } else {
                        if (!"chunk".equals(row.getString("type")) || row.getLong("offset") != output.size())
                            throw new IOException("Invalid configuration chunk offset");
                        String encoded = row.getString("data");
                        if (encoded.length() > CHUNK_BYTES / 3 * 4) throw new IOException("Configuration chunk too large");
                        byte[] bytes;
                        try { bytes = Base64.decode(encoded, Base64.NO_WRAP); }
                        catch (IllegalArgumentException e) { throw new IOException("Invalid base64 chunk", e); }
                        if (bytes.length == 0 || bytes.length > CHUNK_BYTES || bytes.length > expected - output.size()
                            || !encoded.equals(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                            throw new IOException("Invalid configuration chunk");
                        output.write(bytes);
                    }
                }
                if (!page.has("next_offset")) throw new IOException("Missing snapshot continuation");
                if (page.isNull("next_offset")) {
                    if (seen != total || output.size() != expected) throw new IOException("Incomplete configuration snapshot");
                    String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
                    new JSONObject(json).getJSONArray(rootKey);
                    return new Document(metadata, epoch, cursor, json);
                }
                if (page.getInt("next_offset") != seen || seen >= total) throw new IOException("Invalid next snapshot offset");
                page = call("resource_snapshot_page", new JSONObject().put("snapshot_id", id).put("offset", seen).put("max_bytes", 128 << 10));
            }
        } finally {
            try { call("resource_snapshot_release", new JSONObject().put("snapshot_id", id)); }
            catch (IOException | JSONException ignored) { /* expiry releases a disconnected view */ }
        }
    }
    private Version commit(String command, JSONObject request) throws IOException, JSONException {
        try { return new Version(call(command, request)); }
        catch (RemoteFailure failure) { throw failure; }
        catch (IOException | JSONException failure) { throw new CommitUnknown(failure); }
    }
    public Version apply(String revision, JSONArray operations) throws IOException, JSONException {
        return commit("vm_config_apply", new JSONObject().put("revision", revision).put("operations", operations));
    }
    public Version replace(String revision, JSONObject document) throws IOException, JSONException {
        document.getJSONArray("vms");
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Configuration exceeds upload limit");
        JSONObject result = upload("vm_config_upload", new JSONObject().put("revision", revision), bytes);
        try { return new Version(result); }
        catch (IOException | JSONException failure) { throw new CommitUnknown(failure); }
    }

    /** Shared staging protocol; a returned response means commit was attempted exactly once. */
    JSONObject upload(String prefix, JSONObject options, byte[] bytes) throws IOException, JSONException {
        JSONObject begin = call(prefix + "_begin", options.put("bytes", bytes.length));
        String id = begin.getString("upload_id");
        boolean commitAttempted = false;
        try {
            int chunkSize = begin.getInt("chunk_bytes");
            if (chunkSize <= 0 || chunkSize > CHUNK_BYTES) throw new IOException("Invalid upload chunk size");
            for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                int count = Math.min(chunkSize, bytes.length - offset);
                JSONObject reply = call(prefix + "_append", new JSONObject().put("upload_id", id).put("offset", offset)
                    .put("data", Base64.encodeToString(bytes, offset, count, Base64.NO_WRAP)));
                if (reply.getLong("received") != (long) offset + count) throw new IOException("Invalid upload acknowledgement");
            }
            JSONObject commitRequest = new JSONObject().put("upload_id", id);
            commitAttempted = true;
            try { return call(prefix + "_commit", commitRequest); }
            catch (RemoteFailure failure) { throw failure; }
            catch (IOException | JSONException failure) { throw new CommitUnknown(failure); }
        } finally {
            if (!commitAttempted) {
                try { call(prefix + "_abort", new JSONObject().put("upload_id", id)); }
                catch (IOException | JSONException ignored) { /* disconnected or expired */ }
            }
        }
    }
}
