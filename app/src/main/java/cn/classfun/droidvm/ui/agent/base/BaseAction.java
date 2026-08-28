// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent.base;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.ui.agent.autogrow.AutoGrowAction;
import cn.classfun.droidvm.ui.agent.password.PasswordAction;

public abstract class BaseAction {
    protected final AgentVM vm;
    protected final AgentActionSpec spec;

    protected BaseAction(@NonNull AgentVM vm, @NonNull AgentActionSpec spec) {
        this.vm = vm;
        this.spec = spec;
    }

    @NonNull
    @SuppressWarnings("unused")
    public AgentVM getVM() {
        return vm;
    }

    /** Shell function body executed as one step of the ordered rescue action list. */
    @NonNull
    protected abstract String buildActionScript();

    /** Drops credentials once the command payload has been built. */
    public void clearSecrets() {
    }

    @NonNull
    private static BaseAction createAction(
        @NonNull AgentVM vm,
        @NonNull AgentActionSpec spec
    ) {
        switch (spec.getType()) {
            case PasswordAction.TYPE:
            case "passwd": // Early AgentVM prototype spelling.
                return new PasswordAction(vm, spec);
            case AutoGrowAction.TYPE:
                return new AutoGrowAction(vm, spec);
            default:
                throw new IllegalArgumentException(fmt(
                    "VM: Unknown action: %s", spec.getType()));
        }
    }

    /** Restores the ordered action queue, including the old vars/ACTION format. */
    @NonNull
    public static List<BaseAction> createActions(@NonNull AgentVM vm) {
        var out = new ArrayList<BaseAction>();
        for (var spec : vm.getActions()) out.add(createAction(vm, spec));
        if (!out.isEmpty()) return out;

        var legacyType = vm.getActionVar("ACTION", null);
        if (legacyType == null)
            throw new IllegalArgumentException("VM: No action specified");
        var legacy = new AgentActionSpec(legacyType);
        if (legacyType.equals("passwd")) {
            legacy.setParam("password", vm.getActionVar("PASSWORD", ""));
            legacy.setParam("normal_users", vm.getActionVar("PASSWD_NORMAL_USERS", "false"));
        }
        out.add(createAction(vm, legacy));
        return out;
    }

    /** Builds one rescue script that runs every action without rebooting between steps. */
    @NonNull
    public static String buildRescueScript(@NonNull List<BaseAction> actions) {
        if (actions.isEmpty()) throw new IllegalArgumentException("VM: No action specified");
        var script = new StringBuilder(String.join("\n",
            "#!/bin/sh",
            "marker() { printf '\\n__DROIDVM_AGENT__:%s\\n' \"$1\"; }",
            "fail() {",
            "    code=$1",
            "    PASSWORD=",
            "    sync",
            "    umount /mnt >/dev/null 2>&1 || true",
            "    umount /mnt-autogrow >/dev/null 2>&1 || true",
            "    marker \"ACTION:ERROR:$ACTION_INDEX:$ACTION_TYPE:$code\"",
            "    marker \"RESULT:ERROR:$code\"",
            "    exit 0",
            "}",
            "skip_action() {",
            "    ACTION_SKIPPED=true",
            "    marker \"ACTION:SKIPPED:$ACTION_INDEX:$ACTION_TYPE:$1\"",
            "}",
            "mkdir -p /mnt /mnt-autogrow /run",
            ""
        ));
        for (int i = 0; i < actions.size(); i++) {
            var action = actions.get(i);
            var body = action.buildActionScript();
            script.append(fmt("ACTION_INDEX=%d\n", i));
            script.append(fmt("ACTION_TYPE=%s\n", action.spec.getType()));
            script.append("ACTION_SKIPPED=false\n");
            script.append("marker \"ACTION:START:$ACTION_INDEX:$ACTION_TYPE\"\n");
            script.append(fmt("agent_action_%d() {\n", i));
            script.append(body);
            if (!body.endsWith("\n")) script.append('\n');
            script.append("}\n");
            script.append(fmt("agent_action_%d\n", i));
            script.append("rc=$?\n");
            script.append("[ $rc -eq 0 ] || fail SCRIPT_FAILED\n");
            script.append("[ \"$ACTION_SKIPPED\" = true ] || ")
                .append("marker \"ACTION:OK:$ACTION_INDEX:$ACTION_TYPE\"\n\n");
        }
        script.append("sync\nmarker RESULT:OK\n");
        return script.toString();
    }
}
