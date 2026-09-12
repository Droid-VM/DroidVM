// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.input;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.utils.JsonUtils;

/**
 * What the physical-keyboard grab is doing: the settings, the console that is armed, the
 * keyboards held, and every keyboard the host has. Read-only, so a page can be opened on it
 * without taking anything from anybody.
 */
@AutoService(RequestHandler.class)
public final class KeyboardStatusHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "kbd_status";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        JsonUtils.mergeJSONObject(request.res(), request.getContext().getKeyboard().status());
    }
}
