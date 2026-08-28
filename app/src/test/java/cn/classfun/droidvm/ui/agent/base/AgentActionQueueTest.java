// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.agent.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.ui.agent.autogrow.AutoGrowAction;
import cn.classfun.droidvm.ui.agent.password.PasswordAction;

public final class AgentActionQueueTest {
    @Test
    public void actionsKeepInsertionOrderAndBuildOneShellScript() throws Exception {
        var vm = new AgentVM(VMBackend.QEMU, VMHypervisor.SOFT);
        var password = new PasswordAction(vm);
        password.setPassword("quote-'-$-password");
        password.setChangeNormalUsers(true);
        new AutoGrowAction(vm);

        assertEquals(2, vm.getActions().size());
        assertEquals("chpasswd", vm.getActions().get(0).getType());
        assertEquals("autogrow", vm.getActions().get(1).getType());

        var actions = BaseAction.createActions(vm);
        var script = BaseAction.buildRescueScript(actions);
        assertTrue(script.indexOf("ACTION_TYPE=chpasswd")
            < script.indexOf("ACTION_TYPE=autogrow"));
        assertEquals(1, occurrences(script, "marker RESULT:OK"));

        var shell = new ProcessBuilder("sh", "-n").start();
        shell.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
        shell.getOutputStream().close();
        assertEquals(new String(shell.getErrorStream().readAllBytes(), StandardCharsets.UTF_8),
            0, shell.waitFor());

        for (var action : actions) action.clearSecrets();
        assertFalse(vm.getActions().get(0).hasParam("password"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
