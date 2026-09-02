// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import java.io.IOException;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.daemon.vm.pkg.VMExportTask;
import cn.classfun.droidvm.lib.pkg.PackageConstants;

@AutoService(RequestHandler.class)
public final class ExportHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_export";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var destPath = params.optString("dest_path", "");
        if (!params.has("vm_id"))
            throw new RequestException("missing vm_id");
        if (destPath.isEmpty())
            throw new RequestException("missing dest_path");
        var volumeSize = params.optLong("volume_size", 0);
        if (volumeSize > 0 && volumeSize < PackageConstants.MIN_VOLUME_SIZE)
            throw new RequestException(String.format(
                "volume_size too small: %d (min %d)",
                volumeSize, PackageConstants.MIN_VOLUME_SIZE
            ));
        var store = request.getContext().getExportTaskStore();
        var server = request.getClient().getServer();
        VMExportTask task;
        try {
            task = new VMExportTask(server, params);
        } catch (IOException e) {
            // Building the task walks the disks' backing chains, and the reason one cannot be
            // walked (which image, which missing base) is the whole answer for the caller -
            // a plain exception here would reach it as "internal error".
            throw new RequestException(e.getMessage());
        }
        store.put(task.taskId, task);
        task.startAsync();
        request.res().put("task_id", task.taskId.toString());
    }
}