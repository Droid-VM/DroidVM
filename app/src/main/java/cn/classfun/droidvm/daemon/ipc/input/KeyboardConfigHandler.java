// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.input;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import java.util.ArrayList;

import cn.classfun.droidvm.daemon.input.KeyboardGrabConfig;
import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.utils.JsonUtils;

/**
 * Changes the grab's settings and writes them back. Params, both optional so a caller can change
 * one without knowing the other: {@code enabled} (bool) and {@code escape_chord} (array of Linux
 * {@code KEY_*} codes, all of which must be held to hand the keyboard back; empty binds nothing).
 * Answers with the same picture {@code kbd_status} gives.
 */
@AutoService(RequestHandler.class)
public final class KeyboardConfigHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "kbd_config";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var manager = request.getContext().getKeyboard();
        var current = manager.getConfig();
        var enabled = params.optBoolean("enabled", current.enabled);
        var chord = current.escapeChord;
        var arr = params.optJSONArray("escape_chord");
        if (arr != null) {
            var codes = new ArrayList<Integer>();
            for (int i = 0; i < arr.length(); i++) codes.add(arr.optInt(i, -1));
            chord = codes;
        }
        // Through fromJson so one place decides what a usable chord is, whether it arrives from
        // the file or from here.
        manager.setConfig(KeyboardGrabConfig.fromJson(
            new KeyboardGrabConfig(enabled, chord).toJson()));
        JsonUtils.mergeJSONObject(request.res(), manager.status());
    }
}
