package cn.classfun.droidvm.daemon.ipc.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Base64;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;

/**
 * Forwards native-display input from the UI to the per-VM crosvm process. The daemon is the only
 * listener on crosvm's --input sockets (it pre-binds them before exec'ing crosvm), so it is the
 * only process that can deliver evdev to the guest; the UI sends bytes here instead of writing a
 * socket directly. Params: vm_id, channel (NativeDisplay constants), data (base64 evdev records).
 */
@AutoService(RequestHandler.class)
public final class InputHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_input";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var vmId = params.optString("vm_id", "");
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        var channel = params.optInt("channel", -1);
        var data = Base64.decode(params.optString("data", ""), Base64.NO_WRAP);
        var inst = request.getContext().getVMs().findById(vmId);
        if (inst == null)
            throw new RequestException(fmt("VM not found: %s", vmId));
        // Report whether the bytes actually reached crosvm so the UI can tell a silent drop (peer
        // not connected yet, bad channel, VM not running) from a real delivery.
        request.res().put("delivered", inst.writeNativeInput(channel, data));
    }
}
