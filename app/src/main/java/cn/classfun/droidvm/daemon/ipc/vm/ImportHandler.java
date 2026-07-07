package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.daemon.vm.pkg.VMImportTask;

@AutoService(RequestHandler.class)
public final class ImportHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_import";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        if (params.optString("src_path", "").isEmpty())
            throw new RequestException("missing src_path");
        if (params.optString("target_dir", "").isEmpty())
            throw new RequestException("missing target_dir");
        var store = request.getContext().getImportTaskStore();
        var server = request.getClient().getServer();
        var task = new VMImportTask(server, params);
        store.put(task.taskId, task);
        task.startAsync();
        request.res().put("task_id", task.taskId.toString());
    }
}
