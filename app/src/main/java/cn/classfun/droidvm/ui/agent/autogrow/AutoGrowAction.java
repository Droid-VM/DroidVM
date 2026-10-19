// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent.autogrow;

import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.ui.agent.base.AgentActionSpec;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.AgentScripts;
import cn.classfun.droidvm.ui.agent.base.BaseAction;

/** Grows the physical last data partition and its supported filesystem into trailing space. */
public final class AutoGrowAction extends BaseAction {
    public static final String TYPE = "autogrow";

    /** Appends an autogrow step targeting the first AgentVM disk (/dev/vda). */
    public AutoGrowAction(@NonNull AgentVM vm) {
        this(vm, vm.addAction(TYPE));
    }

    public AutoGrowAction(@NonNull AgentVM vm, @NonNull AgentActionSpec spec) {
        super(vm, spec);
        if (!spec.hasParam("device")) spec.setParam("device", "/dev/vda");
    }

    public void setDevice(@NonNull String device) {
        spec.setParam("device", device);
    }

    @NonNull
    @Override
    protected String buildActionScript() {
        var device = spec.getParam("device", "/dev/vda");
        return fmt("DISK=%s\n", escapedString(device)) + AgentScripts.AUTOGROW;
    }
}
