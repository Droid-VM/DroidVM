// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.daemon.ipc.basic;

import androidx.annotation.NonNull;
import com.google.auto.service.AutoService;
import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;

/** Compatibility handshake while the Java rollback implementation remains available.
 * It does not own persistent VM configuration or implement the native service API. */
@AutoService(RequestHandler.class)
public final class CapabilitiesHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() { return "capabilities"; }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        request.res().put("implementation", "legacy-java");
        request.res().put("success", true);
    }
}
