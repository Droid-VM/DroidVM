// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.input;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.utils.JsonUtils;

/**
 * Takes the physical keyboard for a VM, or hands it back, without a console. Params: {@code
 * vm_id}, {@code screen} (the screen whose keyboard device the keys go to) and {@code grab}
 * (bool, default true).
 *
 * <p>The display console has its own path for this over binder, and prefers it because a binder
 * token dies with the console and releases the grab with it. A grab taken here has no such token:
 * it is held until something releases it -- this command with {@code grab=false}, the VM leaving
 * RUNNING, or the feature being switched off -- which is what makes it the right shape for the
 * CLI and for a test, and the wrong one for a console.</p>
 */
@AutoService(RequestHandler.class)
public final class KeyboardGrabHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "kbd_grab";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var manager = request.getContext().getKeyboard();
        var grab = params.optBoolean("grab", true);
        var vmId = params.optString("vm_id", "");
        var screen = params.optString("screen", "");
        if (!grab) {
            if (vmId.isEmpty()) manager.release("asked over IPC");
            else manager.releaseFor(vmId, screen);
            JsonUtils.mergeJSONObject(request.res(), manager.status());
            return;
        }
        if (vmId.isEmpty())
            throw new RequestException("missing vm_id");
        if (request.getContext().getVMs().findById(vmId) == null)
            throw new RequestException(fmt("VM not found: %s", vmId));
        manager.request(vmId, screen, null);
        JsonUtils.mergeJSONObject(request.res(), manager.status());
    }
}
