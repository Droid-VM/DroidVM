// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent.password;

import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.ui.agent.base.AgentActionSpec;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.AgentScripts;
import cn.classfun.droidvm.ui.agent.base.BaseAction;

public final class PasswordAction extends BaseAction {
    public static final String TYPE = "chpasswd";

    /** Appends a chpasswd step to the VM's ordered action queue. */
    public PasswordAction(@NonNull AgentVM vm) {
        this(vm, vm.addAction(TYPE));
    }

    public PasswordAction(@NonNull AgentVM vm, @NonNull AgentActionSpec spec) {
        super(vm, spec);
        if (!spec.hasParam("password")) spec.setParam("password", "");
        if (!spec.hasParam("normal_users")) spec.setParam("normal_users", "false");
    }

    public void setPassword(@NonNull String password) {
        spec.setParam("password", password);
    }

    public void setChangeNormalUsers(boolean change) {
        spec.setParam("normal_users", String.valueOf(change));
    }

    @NonNull
    public String getPassword() {
        return spec.getParam("password", "");
    }

    @NonNull
    @Override
    protected String buildActionScript() {
        var changeNormalUsers = spec.getParam("normal_users", "false");
        return fmt("CHANGE_NORMAL_USERS=%s\n", escapedString(changeNormalUsers))
            + AgentScripts.CHPASSWD;
    }

    @Override
    public void clearSecrets() {
        spec.clearParam("password");
        vm.clearActionVar("PASSWORD"); // Also scrub a deserialized legacy action.
    }
}
