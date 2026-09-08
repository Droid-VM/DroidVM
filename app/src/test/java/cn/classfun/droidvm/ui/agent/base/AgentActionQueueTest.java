// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.agent.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

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
        assertTrue(script.contains("busybox chroot /mnt $PASSWD_BIN"));
        assertTrue(script.contains("for candidate in /usr/bin/passwd /bin/passwd"));
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

    @Test
    public void anOptionalActionFailureLetsTheQueueFinish() throws Exception {
        var out = runQueue(true);

        assertTrue(out, out.contains("SECOND-RAN"));
        assertTrue(out, out.contains("__DROIDVM_AGENT__:ACTION:ERROR:0:chpasswd:PASSWD_FAILED"));
        assertTrue(out, out.contains("__DROIDVM_AGENT__:ACTION:OK:1:autogrow"));
        assertTrue(out, out.contains("__DROIDVM_AGENT__:RESULT:PARTIAL:chpasswd=PASSWD_FAILED"));
        assertFalse(out, out.contains("RESULT:OK"));
    }

    @Test
    public void aRequiredActionFailureStillStopsTheQueue() throws Exception {
        var out = runQueue(false);

        assertFalse(out, out.contains("SECOND-RAN"));
        assertTrue(out, out.contains("__DROIDVM_AGENT__:RESULT:ERROR:PASSWD_FAILED"));
        assertFalse(out, out.contains("RESULT:PARTIAL"));
    }

    /** Runs a two-action queue whose first action fails, and returns the console output. */
    private static String runQueue(boolean optional) throws Exception {
        var vm = new AgentVM(VMBackend.QEMU, VMHypervisor.SOFT);
        var failing = new ScriptedAction(vm, vm.addAction("chpasswd"), "fail PASSWD_FAILED");
        failing.setOptional(optional);
        var second = new ScriptedAction(vm, vm.addAction("autogrow"), "echo SECOND-RAN");
        second.setOptional(optional);
        var script = BaseAction.buildRescueScript(List.of(failing, second));

        // The rescue VM owns / and is thrown away after the run; a test host is neither, so
        // keep the scratch state and the mount points the script creates inside a temp dir.
        var sandbox = Files.createTempDirectory("droidvm-agent-test");
        script = script
            .replace("STATE_DIR=/run/droidvm-agent", fmt("STATE_DIR=%s/state", sandbox))
            .replace("mkdir -p /mnt /mnt-autogrow", fmt("mkdir -p %s/mnt", sandbox));
        var scriptFile = sandbox.resolve("rescue.sh");
        Files.writeString(scriptFile, script);
        var process = new ProcessBuilder("sh", scriptFile.toString())
            .redirectErrorStream(true)
            .start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return out;
    }

    /** A queue member with a caller-supplied body, so a failure can be staged on demand. */
    private static final class ScriptedAction extends BaseAction {
        private final String body;

        private ScriptedAction(AgentVM vm, AgentActionSpec spec, String body) {
            super(vm, spec);
            this.body = body;
        }

        @NonNull
        @Override
        protected String buildActionScript() {
            return body;
        }
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
