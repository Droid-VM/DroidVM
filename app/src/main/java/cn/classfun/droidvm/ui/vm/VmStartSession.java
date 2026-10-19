// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm;

import java.io.IOException;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/** One GUI start attempt. All blocking methods run on a worker. */
final class VmStartSession {
    interface Backend {
        boolean supported() throws Exception;
        boolean startSupported() throws Exception;
        Observed read() throws Exception;
        void start(Observed observed, VmStartPlan plan, String bootEntry, boolean clearLogs) throws Exception;
    }
    static final class Observed {
        final VMConfig config;
        final VmConfigClient.Snapshot snapshot;
        Observed(VMConfig config, VmConfigClient.Snapshot snapshot) { this.config = config; this.snapshot = snapshot; }
    }
    private final Backend backend;
    private final VMConfig expected;
    private boolean submitted;

    private VmStartSession(Backend backend, VMConfig expected) {
        this.backend = backend;
        this.expected = expected;
    }

    static VmStartSession open(VmConfigClient client, VMConfig selected) throws Exception {
        var id = selected.getId();
        return open(new Backend() {
            public boolean supported() throws Exception { return client.supported(); }
            public boolean startSupported() throws Exception { return client.startSupported(); }
            public Observed read() throws Exception {
                var snapshot = client.read();
                return new Observed(new VMStore(snapshot.document()).findById(id), snapshot);
            }
            public void start(Observed observed, VmStartPlan plan, String bootEntry, boolean clearLogs) throws Exception {
                client.startWithSharedDefaults(observed.snapshot, id.toString(), plan.overrides(), bootEntry, clearLogs);
            }
        }, selected);
    }

    static VmStartSession open(Backend backend, VMConfig selected) throws Exception {
        if (!backend.supported()) return new VmStartSession(null, null);
        if (!backend.startSupported()) throw new IOException("Native configured start is unavailable");
        var persisted = backend.read().config;
        if (persisted == null || !persisted.item.equals(selected.item))
            throw new IOException("VM configuration changed; reload before starting");
        return new VmStartSession(backend, persisted);
    }

    /** Only call after this flow's explicit field save has succeeded. */
    synchronized void savedField(VMConfig selected, String key) throws Exception {
        if (backend == null) return;
        var detached = new VMConfig(selected.toJson());
        var value = detached.item.opt(key, null);
        if (value == null) expected.item.remove(key);
        else expected.item.set(key, value);
    }

    synchronized boolean start(VMConfig selected, String bootEntry, boolean clearLogs) throws Exception {
        if (backend == null) return false;
        if (submitted) throw new IOException("Start already submitted; reload state before retrying");
        var plan = VmStartPlan.between(expected.item, selected.item);
        // Our explicit field saves can advance the global revision. A fresh
        // version is usable only when its entire target still matches our expectation.
        var observed = backend.read();
        var current = observed.config;
        if (current == null || !current.item.equals(expected.item))
            throw new IOException("VM configuration changed during startup checks; reload before starting");
        submitted = true;
        backend.start(observed, plan, bootEntry, clearLogs);
        return true;
    }
}
