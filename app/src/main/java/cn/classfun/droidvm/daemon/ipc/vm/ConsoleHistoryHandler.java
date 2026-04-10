package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class ConsoleHistoryHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_console_history";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        var stream = params.optString("stream", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        var inst = request.getContext().getVMs().findById(vmId);
        if (inst == null)
            throw new RequestException("VM not found");
        var res = request.res();
        res.put("vm_id", vmId);
        if (!stream.isEmpty()) {
            res.put(stream, inst.getStreamBuffer(stream));
        } else {
            var names = inst.getStreamNames();
            for (var name : names)
                res.put(name, inst.getStreamBuffer(name));
        }
    }
}
