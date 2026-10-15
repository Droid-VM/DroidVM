// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.daemon.vm.VMInstance.getVMInstance;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

import cn.classfun.droidvm.lib.hugepage.PoolPreflight;
import cn.classfun.droidvm.daemon.network.NetworkInstanceStore;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.daemon.vm.backend.BackendBase;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;

public final class VMInstanceStore extends DataStore<VMInstance> {
    private static final String TAG = "VMInstanceStore";
    /** How long {@link #stopAll} gives the auto-start sweep to stand down. */
    private static final long AUTO_UP_JOIN_MS = 3000;
    public final ServerContext context;
    // Access through context.getVMs(): loaded instances retain a temporary parsing store.
    final PoolReservations poolReservations = new PoolReservations(PoolPreflight::setReservedPages);
    NetworkInstanceStore networkStore;
    /**
     * Set once the daemon is going down, and never cleared: the sweep reads it to decide whether
     * there is any point starting the next VM. A store that has been through {@link #stopAll} is
     * not one anything should be starting VMs in again.
     */
    private volatile boolean shuttingDown = false;
    /** The auto-start sweep, if one was ever started. See {@link #autoUpAsync}. */
    @Nullable
    private volatile Thread autoUpThread = null;

    public VMInstanceStore(@NonNull ServerContext context) {
        super();
        this.context = context;
        BackendBase.loadAll();
        Log.i(TAG, "VM store initialized");
    }

    public void setEventCallback(@Nullable VMInstance.VMEventCallback cb) {
        context.vmEventCallback = cb;
    }

    public void setNetworkStore(@Nullable NetworkInstanceStore networkStore) {
        this.networkStore = networkStore;
    }

    @Nullable
    public JSONObject findVMByTap(@NonNull String tapName) {
        if (!tapName.startsWith("vmtap-")) return null;
        final JSONObject[] found = {null};
        forEach((vmId, inst) -> {
            if (found[0] != null) return;
            if (inst.getState() == VMState.STOPPED) return;
            var nets = inst.item.opt("networks", DataItem.newArray());
            for (var iter : nets) {
                var net = iter.getValue();
                if (!tapName.equals(net.optString("tap_name", ""))) continue;
                try {
                    var obj = new JSONObject();
                    obj.put("vm_id", vmId.toString());
                    obj.put("vm_name", inst.getName());
                    found[0] = obj;
                } catch (Exception ignored) {
                }
                return;
            }
        });
        return found[0];
    }

    @Nullable
    public String createVM(@NonNull VMConfig config) {
        var vmId = config.getId();
        if (vmId == null) {
            Log.e(TAG, "Cannot create VM: missing id");
            return null;
        }
        var vmIdStr = vmId.toString();
        var existing = findById(vmId);
        if (existing != null) {
            Log.w(TAG, fmt("VM %s already exists", vmIdStr));
            return null;
        }
        var inst = getVMInstance(this, config, vmId);
        add(inst);
        Log.i(TAG, fmt("Created VM: %s [%s]", config.getName(), vmIdStr));
        return vmIdStr;
    }

    @Nullable
    public String modifyVM(@NonNull VMConfig config) {
        var vmId = config.getId();
        if (vmId == null) {
            Log.e(TAG, "Cannot modify VM: missing id");
            return null;
        }
        var vmIdStr = vmId.toString();
        var existing = findById(vmId);
        if (existing == null) {
            Log.w(TAG, fmt("VM %s not found", vmIdStr));
            return null;
        }
        if (existing.getState() != VMState.STOPPED) {
            Log.w(TAG, fmt("VM %s is not stopped, cannot modify", vmIdStr));
            return null;
        }
        removeById(vmId);
        var inst = getVMInstance(this, config, vmId);
        add(inst);
        Log.i(TAG, fmt("Modified VM: %s [%s]", config.getName(), vmIdStr));
        return vmIdStr;
    }

    @NonNull
    public JSONArray listVMs() {
        var arr = new JSONArray();
        forEach((id, inst) -> {
            try {
                arr.put(inst.toInfoJson());
            } catch (JSONException e) {
                Log.w(TAG, "Failed to serialize VM instance", e);
            }
        });
        return arr;
    }

    public void stopAll() {
        Log.i(TAG, "Stopping all VMs...");
        // Before anything is collected. The sweep and workers read this before starting, so
        // setting it first is what stops it handing us a VM to stop after we have looked.
        shuttingDown = true;
        joinAutoUp();
        var toStop = new ArrayList<VMInstance>();
        forEach((id, inst) -> {
            var state = inst.getState();
            if (state == VMState.RUNNING ||
                state == VMState.STARTING ||
                state == VMState.SUSPENDED || state == VMState.REBOOTING) {
                toStop.add(inst);
            }
        });
        for (var inst : toStop)
            inst.stop();
        forEach((id, inst) -> inst.joinThreads(2000));
        clear();
    }

    boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Runs {@link #autoUp} on a thread of its own.
     *
     * <p>The sweep used to wait for the huge-page reserve inside the
     * {@code ServerContext} constructor, which is before the daemon has bound its
     * socket, installed its signal handlers or wired up VM events. Every one of those was held
     * behind VMs that had not started yet: no RPC, no clean answer to SIGTERM, and the state
     * changes of the VMs it did start fired into a callback nobody had set. Behind the socket
     * instead, so the daemon answers while its VMs come up.
     *
     * <p>Started from {@link cn.classfun.droidvm.daemon.server.Server#run} rather than from the
     * context, which also means a daemon whose socket would not bind no longer starts VMs on its
     * way to giving up.</p>
     */
    public void autoUpAsync() {
        var sweep = new Thread(this::autoUp, "VMAutoUp");
        sweep.setDaemon(true);
        autoUpThread = sweep;
        sweep.start();
    }

    /** Waits for a running {@link #autoUpAsync} sweep to notice {@link #shuttingDown} and finish. */
    private void joinAutoUp() {
        var sweep = autoUpThread;
        if (sweep == null || !sweep.isAlive()) return;
        Log.i(TAG, "waiting for the auto-start sweep to stand down");
        try {
            // Generous: the flag is read before each start, and
            // start() sets up taps, registers its budget and hands off to a thread. A sweep still inside
            // start() when this runs out is caught by the collection below, which sees STARTING.
            sweep.join(AUTO_UP_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Requests a start for every VM marked auto_up. Each worker prepares its reserve first.
     *
     * <p>The list is taken first and started afterwards, rather than started from inside the
     * iteration. The daemon is answering RPC by the time this runs, so
     * iterating across it would hold a plain {@code ArrayList} open for that whole time against
     * clients creating and deleting VMs -- and one {@code vm_delete} landing in the middle of it is
     * a {@code ConcurrentModificationException} that ends the sweep and leaves the rest of the VMs
     * down. A snapshot narrows that to the moment it takes to collect.</p>
     *
     * <p>Reserve sizing and waiting live in VMInstance so manual, RPC and automatic starts all
     * grow the target before waiting for pages. Waiting here would wait against a zero target
     * after the last VM stopped.</p>
     */
    public void autoUp() {
        var pending = new ArrayList<VMInstance>();
        forEach((id, inst) -> {
            if (inst.item.optBoolean("auto_up", false) && inst.getState() == VMState.STOPPED)
                pending.add(inst);
        });
        for (var inst : pending) {
            if (shuttingDown) {
                Log.i(TAG, "the daemon is going down; abandoning the rest of the auto-start sweep");
                return;
            }
            if (inst.getState() != VMState.STOPPED) continue;
            Log.i(TAG, fmt("Auto-starting VM %s [%s]", inst.getName(), inst.getId()));
            if (!inst.start())
                Log.w(TAG, fmt("Failed to auto-start VM %s [%s]", inst.getName(), inst.getId()));
        }
    }

    @NonNull
    @Override
    protected VMInstance create() {
        return new VMInstance(this);
    }

    @NonNull
    @Override
    protected VMInstance create(@NonNull JSONObject obj) throws JSONException {
        return new VMInstance(this, obj);
    }

    @NonNull
    @Override
    protected DataStore<VMInstance> createEmpty() {
        var store = new VMInstanceStore(context);
        // DataStore.load() parses into this throwaway store and then replace()s the items over,
        // but each VMInstance keeps the store it was constructed with -- so whatever the loaded
        // instances need to reach through their store has to be inherited here, or it is null for
        // the rest of their life. That is how a VM with NICs ended up failing to start with
        // "has networks but no network store".
        store.networkStore = networkStore;
        return store;
    }

    @NonNull
    @Override
    protected String getTypeName() {
        return "vms";
    }
}
