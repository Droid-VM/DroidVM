package cn.classfun.droidvm.ui.agent.password;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.BaseAction;

public final class PasswordAction extends BaseAction {
    public PasswordAction(@NonNull AgentVM vm) {
        super(vm);
        if (!vm.hasActionVar("MOUNT_ROOT"))
            vm.setActionVar("MOUNT_ROOT", "true");
        if (!vm.hasActionVar("ACTION"))
            vm.setActionVar("ACTION", "passwd");
        if (!vm.hasActionVar("PASSWORD"))
            vm.setActionVar("PASSWORD", "");
        if (!vm.hasActionVar("PASSWD_NORMAL_USERS"))
            vm.setActionVar("PASSWD_NORMAL_USERS", "false");
    }

    public void setPassword(@NonNull String password) {
        vm.setActionVar("PASSWORD", password);
    }

    public void setChangeNormalUsers(boolean change) {
        vm.setActionVar("PASSWD_NORMAL_USERS", String.valueOf(change));
    }

    @Override
    public void checkResult() {
        if (!vm.isResultValue("STARTED", "true"))
            throw new RuntimeException("VM: Agent script failed to start");
        if (vm.isResultValue("ROOT_NOT_FOUND", "true"))
            throw new RuntimeException("VM: Root partition not found");
        if (!vm.isResultValue("ROOT_FOUND", "true"))
            throw new RuntimeException("VM: Root partition mount failed");
        if (vm.isResultValue("PASSWD_FAILED", "true"))
            throw new RuntimeException("VM: Failed to change password");
        if (!vm.isResultValue("PASSWD_SUCCESS", "true"))
            throw new RuntimeException("VM: Unknown error during password change");
        if (!vm.isResultValue("ALL_SUCCESS", "true"))
            throw new RuntimeException("VM: Not all operations completed successfully");
    }
}
