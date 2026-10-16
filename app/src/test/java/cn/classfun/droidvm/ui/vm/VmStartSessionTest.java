// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.UUID;
import org.junit.Test;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

public final class VmStartSessionTest {
    private static final UUID ID = UUID.randomUUID();
    private static VMConfig config() {
        var config = new VMConfig(); config.setId(ID); config.setName("target");
        config.item.set("created_at", 1L); config.item.set("protected_vm", "protected_normal");
        return config;
    }
    private static final class Backend implements VmStartSession.Backend {
        boolean supported = true, startSupported = true, loseReply, failRead, failCapability;
        int reads, starts;
        VmStartSession.Observed current = new VmStartSession.Observed(config(), null), sent;
        VmStartPlan plan;
        String boot;
        boolean clear;
        public boolean supported() throws Exception {
            if (failCapability) throw new IOException("disconnected");
            return supported;
        }
        public boolean startSupported() { return startSupported; }
        public VmStartSession.Observed read() throws Exception {
            reads++;
            if (failRead) throw new IOException("read failed");
            return current;
        }
        public void start(VmStartSession.Observed observed, VmStartPlan plan, String boot, boolean clear) throws Exception {
            starts++; sent = observed; this.plan = plan; this.boot = boot; this.clear = clear;
            if (loseReply) throw new IOException("lost response");
        }
    }

    @Test public void legacyIsExplicitAndCapabilityFailureDoesNotFallback() throws Exception {
        var backend = new Backend(); backend.supported = false;
        assertFalse(VmStartSession.open(backend, config()).start(config(), null, false));
        assertEquals(0, backend.reads); assertEquals(0, backend.starts);
        backend.failCapability = true;
        assertThrows(IOException.class, () -> VmStartSession.open(backend, config()));
        backend.failCapability = false; backend.supported = true; backend.startSupported = false;
        assertThrows(IOException.class, () -> VmStartSession.open(backend, config()));
    }

    @Test public void initialMismatchStopsBeforeGuards() {
        var backend = new Backend(); var selected = config(); selected.setName("stale");
        assertThrows(IOException.class, () -> VmStartSession.open(backend, selected));
        assertEquals(0, backend.starts);
    }

    @Test public void finalEditOrDeletionCannotBeRebased() throws Exception {
        for (boolean deleted : new boolean[]{false, true}) {
            var backend = new Backend(); var selected = config();
            var session = VmStartSession.open(backend, selected);
            var changed = config(); changed.setName("concurrent edit");
            backend.current = new VmStartSession.Observed(deleted ? null : changed, null);
            assertThrows(IOException.class, () -> session.start(selected, null, false));
            assertEquals(0, backend.starts);
        }
    }

    @Test public void finalReadFailureDoesNotSubmit() throws Exception {
        var backend = new Backend(); var session = VmStartSession.open(backend, config()); backend.failRead = true;
        assertThrows(IOException.class, () -> session.start(config(), null, false));
        assertEquals(0, backend.starts);
    }

    @Test public void temporarySelectionUsesExactlyValidatedObservationOnce() throws Exception {
        var backend = new Backend(); var selected = config(); var session = VmStartSession.open(backend, selected);
        selected.item.set("protected_vm", "pseudo_unprotected");
        backend.current = new VmStartSession.Observed(config(), null);
        assertTrue(session.start(selected, "entry", true));
        assertSame(backend.current, backend.sent); assertTrue(backend.plan.pseudoUnprotected);
        assertEquals("entry", backend.boot); assertTrue(backend.clear);
        assertThrows(IOException.class, () -> session.start(selected, "entry", true));
        assertEquals(1, backend.starts);
    }

    @Test public void lostReplyCannotTriggerSecondSubmission() throws Exception {
        var backend = new Backend(); var session = VmStartSession.open(backend, config()); backend.loseReply = true;
        assertThrows(IOException.class, () -> session.start(config(), null, false));
        backend.loseReply = false;
        assertThrows(IOException.class, () -> session.start(config(), null, false));
        assertEquals(1, backend.starts); assertEquals(2, backend.reads);
    }
}
