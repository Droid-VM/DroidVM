package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.daemon.vm.pkg.VMExportTask;

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
        var store = request.getContext().getExportTaskStore();
        var server = request.getClient().getServer();
        var task = new VMExportTask(server, params);
        store.put(task.taskId, task);
        task.startAsync();
        request.res().put("task_id", task.taskId.toString());
    }
}