package cn.classfun.droidvm.daemon.ipc.network;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import org.json.JSONArray;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

/** Returns the captured stdout/stderr of one of a network's helper tools. */
@AutoService(RequestHandler.class)
public final class ToolLogHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "network_tool_log";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var id = params.optString("network_id", "");
        if (id.isEmpty())
            throw new RequestException("missing network_id");
        var tool = params.optString("tool", "");
        if (tool.isEmpty())
            throw new RequestException("missing tool");
        var inst = request.getContext().getNetworks().findById(id);
        if (inst == null)
            throw new RequestException(fmt("network %s not found", id));
        var lines = inst.toolLog(tool);
        if (lines == null)
            throw new RequestException(fmt("tool %s not available", tool));
        request.res().put("lines", new JSONArray(lines));
    }
}
