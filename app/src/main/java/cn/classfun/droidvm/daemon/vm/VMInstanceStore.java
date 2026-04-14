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

import cn.classfun.droidvm.daemon.network.NetworkInstanceStore;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.daemon.vm.backend.BackendBase;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;

public final class VMInstanceStore extends DataStore<VMInstance> {
    private static final String TAG = "VMInstanceStore";
    public final ServerContext context;
    volatile VMInstance.VMEventCallback eventCallback = null;
    NetworkInstanceStore networkStore;

    public VMInstanceStore(@NonNull ServerContext context) {
        super();
        this.context = context;
        BackendBase.loadAll();
        Log.i(TAG, "VM store initialized");
    }

    public void setEventCallback(@Nullable VMInstance.VMEventCallback cb) {
        this.eventCallback = cb;
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
        var toStop = new ArrayList<VMInstance>();
        forEach((id, inst) -> {
            var state = inst.getState();
            if (state == VMState.RUNNING ||
                state == VMState.STARTING ||
                state == VMState.SUSPENDED) {
                toStop.add(inst);
            }
        });
        for (var inst : toStop)
            inst.stop();
        forEach((id, inst) -> inst.joinThreads(2000));
        clear();
    }

    public void autoUp() {
        forEach((id, inst) -> {
            if (!inst.item.optBoolean("auto_up", false) || inst.getState() != VMState.STOPPED) return;
            Log.i(TAG, fmt("Auto-starting VM %s [%s]", inst.getName(), id));
            if (!inst.start())
                Log.w(TAG, fmt("Failed to auto-start VM %s [%s]", inst.getName(), id));
        });
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
        return new VMInstanceStore(context);
    }

    @NonNull
    @Override
    protected String getTypeName() {
        return "vms";
    }
}
