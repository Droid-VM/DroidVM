package cn.classfun.droidvm.ui.agent.base;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.ui.agent.password.PasswordAction;

public abstract class BaseAction {
    protected final AgentVM vm;

    public BaseAction(@NonNull AgentVM vm) {
        this.vm = vm;
    }

    @NonNull
    @SuppressWarnings("unused")
    public AgentVM getVM() {
        return vm;
    }

    public abstract void checkResult();

    @NonNull
    public static BaseAction createAction(@NonNull AgentVM vm) {
        var action = vm.getActionVar("ACTION", null);
        if (action == null)
            throw new IllegalArgumentException("VM: No action specified");
        switch (action) {
            case "passwd":
                return new PasswordAction(vm);
            default:
                throw new IllegalArgumentException(fmt("VM: Unknown action: %s", action));
        }
    }
}
