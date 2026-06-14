package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.daemon.vm.BootPlan;

/**
 * Scans a guest disk image for boot entries (lbx) on behalf of the UI:
 * the edit tab's detection card and the boot menu both need entry lists,
 * and disk images are typically only readable by the daemon.
 */
@AutoService(RequestHandler.class)
public final class BootScanHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "vm_bootscan";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var image = params.optString("image", "");
        if (image.isEmpty())
            throw new RequestException("missing image");
        var entries = BootPlan.scanEntries(image);
        request.res().put("entries", entries);
    }
}
