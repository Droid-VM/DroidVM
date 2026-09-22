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

    /** See {@link AgentActionSpec#setOptional(boolean)}. */
    public void setOptional(boolean optional) {
        spec.setOptional(optional);
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
            "STATE_DIR=/run/droidvm-agent",
            "FAILED_ACTIONS=",
            "marker() { printf '\\n__DROIDVM_AGENT__:%s\\n' \"$1\"; }",
            "command_log() { printf '\\n[droidvm] $ %s\\n' \"$1\"; }",
            "release_mounts() {",
            "    sync",
            "    umount /mnt/proc >/dev/null 2>&1 || true",
            "    umount /mnt/dev >/dev/null 2>&1 || true",
            "    umount /mnt >/dev/null 2>&1 || true",
            "    umount /mnt-autogrow >/dev/null 2>&1 || true",
            "}",
            // fail() and skip_action() run inside the action subshell, so they only record the
            // outcome; end_action() reads it back and decides what it means for the queue.
            "fail() {",
            "    printf '%s\\n' \"$1\" > \"$STATE_DIR/error\"",
            "    exit 1",
            "}",
            "skip_action() {",
            "    printf '%s\\n' \"$1\" > \"$STATE_DIR/skipped\"",
            "}",
            "begin_action() {",
            "    rm -f \"$STATE_DIR/error\" \"$STATE_DIR/skipped\"",
            "    marker \"ACTION:START:$ACTION_INDEX:$ACTION_TYPE\"",
            "}",
            "end_action() {",
            "    code=$(cat \"$STATE_DIR/error\" 2>/dev/null)",
            "    [ -n \"$code\" ] || [ \"$1\" -eq 0 ] || code=SCRIPT_FAILED",
            "    if [ -n \"$code\" ]; then",
            "        release_mounts",
            "        marker \"ACTION:ERROR:$ACTION_INDEX:$ACTION_TYPE:$code\"",
            // A required action decides the whole run; an optional one only leaves a note,
            // so the actions queued behind it still get their turn.
            "        if [ \"$ACTION_OPTIONAL\" != true ]; then",
            "            marker \"RESULT:ERROR:$code\"",
            "            exit 0",
            "        fi",
            "        FAILED_ACTIONS=\"${FAILED_ACTIONS:+$FAILED_ACTIONS,}$ACTION_TYPE=$code\"",
            "        return 0",
            "    fi",
            "    if [ -s \"$STATE_DIR/skipped\" ]; then",
            "        marker \"ACTION:SKIPPED:$ACTION_INDEX:$ACTION_TYPE:$(cat \"$STATE_DIR/skipped\")\"",
            "    else",
            "        marker \"ACTION:OK:$ACTION_INDEX:$ACTION_TYPE\"",
            "    fi",
            "}",
            "mkdir -p /mnt /mnt-autogrow \"$STATE_DIR\"",
            ""
        ));
        for (int i = 0; i < actions.size(); i++) {
            var action = actions.get(i);
            var body = action.buildActionScript();
            script.append(fmt("ACTION_INDEX=%d\n", i));
            script.append(fmt("ACTION_TYPE=%s\n", action.spec.getType()));
            script.append(fmt("ACTION_OPTIONAL=%s\n", action.spec.isOptional()));
            script.append("begin_action\n");
            script.append(fmt("agent_action_%d() {\n", i));
            script.append(body);
            if (!body.endsWith("\n")) script.append('\n');
            script.append("}\n");
            // The subshell keeps fail()'s exit inside its own action.
            script.append(fmt("( agent_action_%d )\n", i));
            script.append("end_action $?\n\n");
        }
        script.append(String.join("\n",
            "sync",
            "if [ -n \"$FAILED_ACTIONS\" ]; then",
            "    marker \"RESULT:PARTIAL:$FAILED_ACTIONS\"",
            "else",
            "    marker RESULT:OK",
            "fi",
            ""
        ));
        return script.toString();
    }
}
