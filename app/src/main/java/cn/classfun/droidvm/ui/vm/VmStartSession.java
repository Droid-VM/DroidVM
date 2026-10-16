// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm;

import java.io.IOException;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/** One GUI start attempt. All blocking methods run on a worker. */
final class VmStartSession {
    private final VmConfigClient client;
    private final VMConfig expected;
    private boolean submitted;

    private VmStartSession(VmConfigClient client, VMConfig expected) {
        this.client = client;
        this.expected = expected;
    }

    static VmStartSession open(VmConfigClient client, VMConfig selected) throws Exception {
        if (!client.supported()) return new VmStartSession(null, null);
        if (!client.startSupported()) throw new IOException("Native configured start is unavailable");
        var snapshot = client.read();
        var persisted = new VMStore(snapshot.document()).findById(selected.getId());
        if (persisted == null || !persisted.item.equals(selected.item))
            throw new IOException("VM configuration changed; reload before starting");
        return new VmStartSession(client, persisted);
    }

    /** Only call after this flow's explicit field save has succeeded. */
    synchronized void savedField(VMConfig selected, String key) throws Exception {
        if (client == null) return;
        var detached = new VMConfig(selected.toJson());
        var value = detached.item.opt(key, null);
        if (value == null) expected.item.remove(key);
        else expected.item.set(key, value);
    }

    synchronized boolean start(VMConfig selected, String bootEntry, boolean clearLogs) throws Exception {
        if (client == null) return false;
        if (submitted) throw new IOException("Start already submitted; reload state before retrying");
        var plan = VmStartPlan.between(expected.item, selected.item);
        // Our explicit field saves can advance the global revision. A fresh
        // version is usable only when its entire target still matches our expectation.
        var snapshot = client.read();
        var current = new VMStore(snapshot.document()).findById(expected.getId());
        if (current == null || !current.item.equals(expected.item))
            throw new IOException("VM configuration changed during startup checks; reload before starting");
        submitted = true;
        client.start(snapshot, expected.getId().toString(), plan.overrides(), bootEntry, clearLogs);
        return true;
    }
}
