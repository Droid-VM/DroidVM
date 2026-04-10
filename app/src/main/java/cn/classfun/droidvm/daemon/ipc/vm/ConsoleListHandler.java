package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.json.JSONArray;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class ConsoleListHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_console_list";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        var inst = request.getContext().getVMs().findById(vmId);
        if (inst == null)
            throw new RequestException("VM not found");
        var names = inst.getStreamNames();
        var arr = new JSONArray();
        for (var name : names)
            arr.put(name);
        var res = request.res();
        res.put("data", arr);
    }
}
