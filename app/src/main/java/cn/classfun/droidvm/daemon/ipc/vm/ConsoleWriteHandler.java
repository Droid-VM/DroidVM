package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class ConsoleWriteHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_console_write";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        var stream = params.optString("stream", "");
        var data = params.optString("data", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        if (stream.isEmpty())
            throw new RequestException("missing stream");
        var inst = request.getContext().getVMs().findById(vmId);
        if (inst == null)
            throw new RequestException("VM not found");
        if (!inst.writeStream(stream, data))
            throw new RequestException("failed to write to stream");
    }
}
