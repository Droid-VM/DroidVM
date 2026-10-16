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
        if (response == null) throw new IOException("Missing configuration response");
        if (!response.getBoolean("success")) throw new RemoteFailure(response);
        return response;
    }
    public boolean supported() throws IOException, JSONException {
        JSONObject capabilities = call("capabilities", new JSONObject());
        return "base64-json-chunks-v1".equals(capabilities.optString("vm_config_snapshot"))
            && "revisioned-chunks-v1".equals(capabilities.optString("vm_config_upload"))
            && "revisioned-batch-v1".equals(capabilities.optString("vm_config_updates"));
    }
    public Version status() throws IOException, JSONException { return new Version(call("vm_config_status", new JSONObject())); }

    public Snapshot read() throws IOException, JSONException {
        JSONObject page = call("vm_config_snapshot", new JSONObject().put("max_bytes", 128 << 10));
        String id = page.getString("snapshot_id");
        try {
            String epoch = page.getString("epoch"), cursor = page.getString("cursor");
            int total = page.getInt("total"), seen = 0, expected = -1;
            if (total < 1 || total > 1 + (MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES)
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
                        if (size <= 0 || size > MAX_BYTES) throw new IOException("Invalid configuration size");
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
                if (page.isNull("next_offset")) {
                    if (seen != total || output.size() != expected) throw new IOException("Incomplete configuration snapshot");
                    String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
                    new JSONObject(json).getJSONArray("vms");
                    return new Snapshot(metadata, epoch, cursor, json);
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
        JSONObject begin = call("vm_config_upload_begin", new JSONObject().put("revision", revision).put("bytes", bytes.length));
        String id = begin.getString("upload_id");
        boolean commitAttempted = false;
        try {
            int chunkSize = begin.getInt("chunk_bytes");
            if (chunkSize <= 0 || chunkSize > CHUNK_BYTES) throw new IOException("Invalid upload chunk size");
            for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                int count = Math.min(chunkSize, bytes.length - offset);
                JSONObject reply = call("vm_config_upload_append", new JSONObject().put("upload_id", id).put("offset", offset)
                    .put("data", Base64.encodeToString(bytes, offset, count, Base64.NO_WRAP)));
                if (reply.getLong("received") != (long) offset + count) throw new IOException("Invalid upload acknowledgement");
            }
            JSONObject commitRequest = new JSONObject().put("upload_id", id);
            commitAttempted = true;
            return commit("vm_config_upload_commit", commitRequest);
        } finally {
            if (!commitAttempted) {
                try { call("vm_config_upload_abort", new JSONObject().put("upload_id", id)); }
                catch (IOException | JSONException ignored) { /* disconnected or expired */ }
            }
        }
    }
}
