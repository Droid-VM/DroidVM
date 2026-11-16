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

    /**
     * Only call after this flow's explicit field save has succeeded.
     *
     * <p>Adopt the whole row the daemon now holds instead of copying just {@code key}: saving goes
     * through the daemon's {@code migrate_vm}, which may add or rewrite fields the App model does
     * not carry (boot/uefi/serial migrations). Copying one field left {@code expected} different
     * from the persisted row, so the start check then failed with "VM configuration changed during
     * startup checks" — every image-booting (non-Windows) guest that showed the boot menu and
     * remembered a choice hit this, while disk/UEFI guests never saved anything and started fine.
     */
    synchronized void savedField(VMConfig selected, String key) throws Exception {
        if (backend == null) return;
        var observed = backend.read();
        if (observed.config == null) throw new IOException("VM no longer exists; reload before starting");
        // Adopt the row the daemon actually stores into BOTH sides of the later comparison. Saving
        // runs the daemon's migrate_vm (use_uefi / boot / screens / serial / xhci), so the stored row
        // is not byte-identical to what the App uploaded; copying one field (or only `expected`)
        // leaves the start check or VmStartPlan looking at two different shapes.
        expected.item.set(observed.config.item);
        selected.item.set(observed.config.item);
    }

    synchronized boolean start(VMConfig selected, String bootEntry, boolean clearLogs) throws Exception {
        if (backend == null) return false;
        if (submitted) throw new IOException("Start already submitted; reload state before retrying");
        var observed = backend.read();
        var current = observed.config;
        if (current == null) throw new IOException("VM no longer exists; reload before starting");
        // The daemon rewrites a VM row whenever it stores it (store/vm_config.cpp migrate_vm:
        // use_uefi / boot / nic forwards / screens / serial defaults / xhci). Any save made by the
        // start flow itself therefore leaves `expected` one migration behind what is on disk, and
        // a byte-exact comparison rejected the start with "configuration changed during startup
        // checks" - which is exactly what every image-booting guest (boot menu -> remembered
        // choice) hit. Rebase on the daemon's current row and compute the overrides from there;
        // the daemon applies them against that same revision, so nothing stale is written.
        if (!current.item.equals(expected.item)) {
            // Only re-base what the user has not re-selected since the session opened: if the
            // selection still equals the old expectation there are no unsaved edits to protect,
            // and the difference is just the daemon's row migration.
            var selectionUnchanged = selected.item.equals(expected.item);
            android.util.Log.w("DVM-START", "rebase: expected=" + expected.item.toJson() + " current=" + current.item.toJson());
            expected.item.set(current.item);
            if (selectionUnchanged) selected.item.set(current.item);
        }
        var plan = VmStartPlan.between(expected.item, selected.item);
        submitted = true;
        backend.start(observed, plan, bootEntry, clearLogs);
        return true;
    }
}
