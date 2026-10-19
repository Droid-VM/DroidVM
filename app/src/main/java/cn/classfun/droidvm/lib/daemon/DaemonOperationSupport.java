// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;

/** Small shared admission/identity checks; each domain still validates its own receipt. */
final class DaemonOperationSupport {
    private DaemonOperationSupport() {}
    static JSONObject call(VmConfigClient.Rpc rpc, JSONObject request) throws IOException, JSONException {
        JSONObject response = rpc.request(request);
        if (response == null || !(response.opt("success") instanceof Boolean)) throw new IOException("Invalid daemon operation response");
        if (!response.getBoolean("success")) throw new VmConfigClient.RemoteFailure(response);
        return response;
    }
    static boolean rejectedBeforeMutation(String code) {
        return "INVALID_ARGUMENT".equals(code) || "CONFLICT".equals(code) || "RESOURCE_EXHAUSTED".equals(code)
            || "UNAVAILABLE".equals(code) || "UNAUTHENTICATED".equals(code) || "PERMISSION_DENIED".equals(code);
    }
    static JSONObject task(JSONObject response, String requestId, String kind, String resource, String epoch, String id)
        throws IOException, JSONException {
        JSONObject task = response.getJSONObject("task");
        String actualEpoch = task.getString("epoch"), actualId = task.getString("task_id");
        if (!requestId.equals(task.optString("request_id")) || !kind.equals(task.optString("kind"))
            || !resource.equals(task.optString("resource")) || actualEpoch.isEmpty() || actualId.isEmpty()
            || !actualEpoch.equals(response.optString("epoch")) || !actualId.equals(response.optString("task_id"))
            || (epoch != null && (!epoch.equals(actualEpoch) || !id.equals(actualId)))) throw new IOException("Daemon task identity mismatch");
        return task;
    }
}
