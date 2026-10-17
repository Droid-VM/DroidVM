// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

/** Worker-only joint registry edits. No image operations, local fallback or automatic retries. */
public final class ConfigurationClient {
    public static final int MAX_BYTES = VmConfigClient.MAX_BYTES + DiskConfigClient.MAX_BYTES + 1024;
    private final VmConfigClient.Rpc rpc;
    public ConfigurationClient(VmConfigClient.Rpc rpc) { this.rpc = rpc; }

    /** Absence means this operation is unavailable, not permission to use legacy writes. */
    public boolean supported() throws IOException, JSONException {
        var caps = rpc.request(new JSONObject().put("command", "capabilities"));
        if (caps == null || !(caps.opt("success") instanceof Boolean)) throw new IOException("Invalid capabilities response");
        if (!caps.getBoolean("success")) throw new VmConfigClient.RemoteFailure(caps);
        if (!caps.has("configuration_vm_disks")) {
            var keys = caps.keys();
            while (keys.hasNext()) if (keys.next().startsWith("configuration_vm_disks"))
                throw new IOException("Unsupported joint configuration API");
            return false;
        }
        if (!"revisioned-chunks-v1".equals(caps.optString("configuration_vm_disks"))
                || !"base64-json-chunks-v1".equals(caps.optString("vm_config_snapshot"))
                || !"base64-json-chunks-v1".equals(caps.optString("disk_config_snapshot")))
            throw new IOException("Unsupported joint configuration API");
        return true;
    }

    public EditSession edit() throws IOException, JSONException {
        if (!supported()) throw new IOException("Joint VM/disk configuration is unavailable");
        var vms = new VmConfigClient(rpc).read();
        var disks = new DiskConfigClient(rpc).read();
        if (!vms.epoch.equals(disks.epoch)) throw new IOException("Daemon changed while reading configuration; reload");
        // The snapshots need not share a cursor: both exact revisions must still
        // match together when the coordinator admits the eventual commit.
        return new EditSession(vms, disks);
    }

    public static final class Result {
        public final VmConfigClient.Version vms, disks;
        private Result(JSONObject response) throws IOException, JSONException {
            vms = new VmConfigClient.Version(response.getJSONObject("vms"));
            disks = new VmConfigClient.Version(response.getJSONObject("disks"));
            if (!vms.persisted || !disks.persisted || vms.durabilityUncertain || disks.durabilityUncertain)
                throw new IOException("Joint configuration durability is not confirmed; reload");
        }
    }

    public final class EditSession {
        private final VmConfigClient.Snapshot vmBaseline;
        private final DiskConfigClient.Snapshot diskBaseline;
        private boolean submitted;
        private EditSession(VmConfigClient.Snapshot vms, DiskConfigClient.Snapshot disks) {
            vmBaseline = vms; diskBaseline = disks;
        }
        public JSONObject vms() throws JSONException { return vmBaseline.document(); }
        public JSONObject disks() throws JSONException { return diskBaseline.document(); }
        /** Settings import: null means retain that registry, matching IDs replace complete rows. */
        public synchronized Result merge(JSONArray importedVms, JSONArray importedDisks) throws IOException, JSONException {
            var vms = vms(); var disks = disks();
            if (importedVms != null) VmConfigClient.mergeRows(vms.getJSONArray("vms"), importedVms, null);
            if (importedDisks != null) VmConfigClient.mergeRows(disks.getJSONArray("disks"), importedDisks, null);
            return save(vms, disks);
        }
        public synchronized Result save(JSONObject vms, JSONObject disks) throws IOException, JSONException {
            if (submitted) throw new IOException("Reload both configurations before saving again");
            vms.getJSONArray("vms"); disks.getJSONArray("disks");
            var vmJson = vms.toString(); var diskJson = disks.toString();
            if (vmJson.getBytes(StandardCharsets.UTF_8).length > VmConfigClient.MAX_BYTES
                    || diskJson.getBytes(StandardCharsets.UTF_8).length > DiskConfigClient.MAX_BYTES)
                throw new IOException("Configuration document exceeds its storage limit");
            var payload = new JSONObject().put("vms", new JSONObject(vmJson)).put("disks", new JSONObject(diskJson));
            var bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Joint configuration exceeds upload limit");
            submitted = true;
            final JSONObject response;
            try {
                response = new VmConfigClient(rpc).upload("configuration_vm_disks", new JSONObject()
                    .put("vm_revision", vmBaseline.revision).put("disk_revision", diskBaseline.revision), bytes);
            } catch (VmConfigClient.RemoteFailure failure) {
                if ("INTERNAL".equals(failure.code) || "UNKNOWN".equals(failure.code))
                    throw new VmConfigClient.CommitUnknown(failure);
                throw failure;
            }
            try { return new Result(response); }
            catch (IOException | JSONException failure) { throw new VmConfigClient.CommitUnknown(failure); }
        }
    }
}
