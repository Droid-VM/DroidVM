// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.agent.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.ui.agent.autogrow.AutoGrowAction;
import cn.classfun.droidvm.ui.agent.password.PasswordAction;

public final class AgentActionQueueTest {
    @Test
    public void encodedPayloadIsSplitBelowTheCanonicalTtyLimit() {
        var payload = "A".repeat(AgentPayloadChunks.MAX_CHUNK_LENGTH * 3 + 17);
        var chunks = AgentPayloadChunks.split(payload);
        var rebuilt = new StringBuilder();

        assertEquals(4, chunks.size());
        for (var chunk : chunks) {
            assertTrue(chunk.length() <= AgentPayloadChunks.MAX_CHUNK_LENGTH);
            rebuilt.append(chunk);
        }
        assertEquals(payload, rebuilt.toString());
    }

    @Test
    public void operationConsoleIsSelectedByOperationOwner() {
        var vm = new AgentVM(VMBackend.CROSVM, VMHypervisor.AUTO);
        vm.setOperationConsole("serial1", "/dev/ttyS0");

        assertEquals("serial1", vm.getOperationConsoleStream());
        assertEquals("/dev/ttyS0", vm.getOperationConsoleDevice());
        assertEquals("console=ttyS0 rdinit=/bin/sh panic=-1",
            BootConfig.of(vm.buildVM()).getCmdline());
    }

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
        assertTrue(script.contains("busybox chroot /mnt /usr/bin/passwd"));
        assertTrue(script.contains("mount -t proc proc /mnt/proc"));
        assertFalse(script.contains("passwd --root"));
        assertTrue(script.contains("COMMAND:RC:PASSWD"));
        assertTrue(script.contains("COMMAND:RC:RESIZE2FS"));
        assertFalse(script.contains("quote-'-$-password"));

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
