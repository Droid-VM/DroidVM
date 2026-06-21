package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.json.JSONArray;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.daemon.vm.BootPlan;

/**
 * Reports which of the given disk images use qcow2 features the crosvm
 * backend can't read (zlib-compressed clusters) for the UI's pre-start
 * guard: such an image boots to a dead end (vda I/O errors, no partition
 * table, root device never appears). lbx already runs in the daemon for
 * boot scans, and disk images are usually only readable here, so the check
 * lives daemon-side. This is never used on the URL-analysis path.
 */
@AutoService(RequestHandler.class)
public final class DiskCompatHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "disk_compat";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var images = request.getParams().optJSONArray("images");
        var compressed = new JSONArray();
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                var path = images.optString(i, "");
                if (!path.isEmpty() && BootPlan.hasCompressedClusters(path))
                    compressed.put(path);
            }
        }
        request.res().put("compressed", compressed);
    }
}
