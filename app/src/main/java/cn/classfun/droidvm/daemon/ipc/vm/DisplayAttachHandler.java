package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.display.NativeDisplayBinder;
import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

/**
 * Hands the daemon's native-display binder to the UI. A live IBinder can't ride this JSON-RPC
 * channel, so the daemon broadcasts it (through system_server) to the requesting Activity instead;
 * the Activity proves which broadcast is its own with [nonce]. Params: nonce (per-attach token).
 */
@AutoService(RequestHandler.class)
public final class DisplayAttachHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "display_attach";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var nonce = request.getParams().optString("nonce", "");
        if (nonce.isEmpty())
            throw new RequestException("missing nonce");
        NativeDisplayBinder.attach(request.getContext(), nonce);
    }
}
