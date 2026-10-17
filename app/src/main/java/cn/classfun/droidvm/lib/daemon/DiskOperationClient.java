// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Worker-only native disk operations (API 25). The daemon owns leases, the image tool, the
 * journal and the registry follow-up; this client never runs image commands or edits the
 * registry itself, and never resubmits an operation whose admission is unknown.
 */
public final class DiskOperationClient {
    public static final String CAPABILITY = "revisioned-tasks-v1";
    private final VmConfigClient.Rpc rpc;

    public DiskOperationClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }

    /** The operation might have been admitted; check the disk list before retrying. */
    public static final class SubmitUnknown extends IOException {
        SubmitUnknown(Exception cause) { super("Disk operation submission result unknown; reload disks before retrying", cause); }
    }

    public static final class Task {
        public final String epoch, id;
        Task(String epoch, String id) { this.epoch = epoch; this.id = id; }
    }

    public static final class Status {
        public final String state, phase, errorCode, message;
        public final JSONObject result;
        Status(JSONObject task) {
            state = task.optString("state", "");
            phase = task.optString("phase", "");
            var error = task.optJSONObject("error");
            errorCode = error == null ? null : error.optString("code", "UNKNOWN");
            message = error == null ? null : error.optString("message", "");
            result = task.optJSONObject("result");
        }
        public boolean terminal() { return "succeeded".equals(state) || "failed".equals(state) || "cancelled".equals(state); }
        public boolean succeeded() { return "succeeded".equals(state); }
        /** Files or registry may be half done; only daemon recovery may finish them. */
        public boolean needsRecovery() { return "COMMIT_UNCERTAIN".equals(errorCode); }
    }

    private JSONObject call(JSONObject request) throws IOException, JSONException {
        var response = rpc.request(request);
        if (response == null || !(response.opt("success") instanceof Boolean))
            throw new IOException("Missing or invalid disk operation response");
        if (!response.getBoolean("success")) throw new VmConfigClient.RemoteFailure(response);
        return response;
    }

    /** Actions the daemon advertises; empty when native disk operations are not offered. */
    public Set<String> actions() throws IOException, JSONException {
        var caps = call(new JSONObject().put("command", "capabilities"));
        var result = new HashSet<String>();
        if (!caps.has("disk_operations")) return result;
        if (!CAPABILITY.equals(caps.optString("disk_operations")))
            throw new IOException("Disk operation API is incompatible; update the daemon before continuing");
        var list = caps.optJSONArray("disk_operation_actions");
        for (int i = 0; list != null && i < list.length(); i++) result.add(list.getString(i));
        return result;
    }

    /**
     * Submits once against the current registry revision. Admission failures (stale revision,
     * busy disk, header/registry mismatch) arrive as {@link VmConfigClient.RemoteFailure}.
     */
    public Task start(JSONObject operation) throws IOException, JSONException {
        return start(operation, new DiskConfigClient(rpc).status().revision);
    }

    /** Same, admitted only if the registry is still exactly the confirmed revision. */
    public Task start(JSONObject operation, String revision) throws IOException, JSONException {
        var request = new JSONObject(operation.toString())
            .put("command", "disk_operation_start").put("disk_revision", revision);
        final JSONObject response;
        try {
            response = call(request);
        } catch (VmConfigClient.RemoteFailure failure) {
            if ("INTERNAL".equals(failure.code) || "UNKNOWN".equals(failure.code)) throw new SubmitUnknown(failure);
            throw failure;
        } catch (IOException failure) {
            throw new SubmitUnknown(failure);
        }
        String epoch = response.optString("epoch", ""), id = response.optString("task_id", "");
        if (epoch.isEmpty() || id.isEmpty()) throw new SubmitUnknown(new IOException("Missing task reference"));
        return new Task(epoch, id);
    }

    public Status status(Task task) throws IOException, JSONException {
        return new Status(call(new JSONObject().put("command", "task_get")
            .put("epoch", task.epoch).put("task_id", task.id)).getJSONObject("task"));
    }

    /** Polls until the task ends. Status loss is reported as an exception, never as failure. */
    public Status await(Task task) throws IOException, JSONException, InterruptedException {
        for (;;) {
            var status = status(task);
            if (status.terminal()) return status;
            Thread.sleep(250);
        }
    }

    /** Cooperative; operations past their commit point finish regardless. */
    public Status cancel(Task task) throws IOException, JSONException {
        return new Status(call(new JSONObject().put("command", "task_cancel")
            .put("epoch", task.epoch).put("task_id", task.id)).getJSONObject("task"));
    }

    /** Replays interrupted operations; returns how many still need attention. */
    public long recover() throws IOException, JSONException {
        return call(new JSONObject().put("command", "disk_operation_recover")).getLong("pending");
    }
}
