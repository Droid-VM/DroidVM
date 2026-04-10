package cn.classfun.droidvm.daemon.ipc.basic;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;

@AutoService(RequestHandler.class)
public final class PingHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "ping";
    }

    @Override
    public boolean needAuthorization() {
        return false;
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var res = request.res();
        if (params.optString("request").equals("PING"))
            res.put("response", "PONG");
        else
            res.put("response", "HELLO");
    }
}
