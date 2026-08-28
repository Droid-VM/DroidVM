// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent.base;

import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_KERNEL;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.store.vm.LendMthpMode;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class AgentVM implements JSONSerialize {
    private List<DiskConfig> disks = new ArrayList<>();
    private List<AgentActionSpec> actions = new ArrayList<>();
    private Map<String, String> vars = new HashMap<>();
    private String randomId = null;
    private VMBackend backend;
    private VMHypervisor hypervisor;

    public AgentVM() {
        this(VMBackend.DEFAULT, VMHypervisor.AUTO);
    }

    public AgentVM(
        @NonNull VMBackend backend,
        @NonNull VMHypervisor hypervisor
    ) {
        this.backend = backend;
        this.hypervisor = hypervisor;
    }

    public AgentVM(@NonNull DiskStore store, @NonNull JSONObject jo) throws JSONException {
        this();
        if (jo.has("id"))
            randomId = jo.getString("id");
        if (jo.has("backend"))
            backend = VMBackend.valueOf(jo.getString("backend").toUpperCase(Locale.ROOT));
        if (jo.has("hypervisor"))
            hypervisor = VMHypervisor.valueOf(
                jo.getString("hypervisor").toUpperCase(Locale.ROOT));
        if (jo.has("disks")) this.disks = JsonUtils.arrayToList(jo, "disks", v -> {
            var disk = store.findById((String) v);
            if (disk == null) throw new JSONException(fmt(
                "Disk with id %s not found", v
            ));
            return disk;
        });
        if (jo.has("actions")) this.actions = JsonUtils.arrayToList(
            jo, "actions", v -> new AgentActionSpec((JSONObject) v));
        if (jo.has("vars"))
            this.vars = JsonUtils.objectToStringMap(jo, "vars");
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var jo = new JSONObject();
        if (randomId != null)
            jo.put("id", randomId);
        jo.put("backend", backend.name().toLowerCase(Locale.ROOT));
        jo.put("hypervisor", hypervisor.name().toLowerCase(Locale.ROOT));
        var disksArr = new JSONArray();
        for (var disk : disks)
            disksArr.put(disk.getId().toString());
        jo.put("disks", disksArr);
        var actionsArr = new JSONArray();
        for (var action : actions)
            actionsArr.put(action.toJson());
        jo.put("actions", actionsArr);
        var varsObj = new JSONObject();
        for (var entry : vars.entrySet())
            varsObj.put(entry.getKey(), entry.getValue());
        jo.put("vars", varsObj);
        return jo;
    }

    private String getRandomId() {
        if (randomId == null) {
            var random = new Random();
            var b = new byte[8];
            random.nextBytes(b);
            var sb = new StringBuilder();
            for (byte x : b) sb.append(fmt("%02x", x));
            randomId = sb.toString();
        }
        return randomId;
    }

    @NonNull
    private String getName() {
        return fmt("agent-%s", getRandomId());
    }

    public void addDisk(@NonNull DiskConfig disk) {
        disks.add(disk);
    }

    /** Appends an operation; list order is execution order inside the same rescue VM. */
    @NonNull
    public AgentActionSpec addAction(@NonNull String type) {
        var action = new AgentActionSpec(type);
        actions.add(action);
        return action;
    }

    @NonNull
    public List<AgentActionSpec> getActions() {
        return Collections.unmodifiableList(actions);
    }

    @NonNull
    public VMBackend getBackend() {
        return backend;
    }

    public void setBackend(@NonNull VMBackend backend) {
        this.backend = backend;
    }

    @NonNull
    public VMHypervisor getHypervisor() {
        return hypervisor;
    }

    public void setHypervisor(@NonNull VMHypervisor hypervisor) {
        this.hypervisor = hypervisor;
    }

    @NonNull
    public VMConfig buildVM() {
        var vm = new VMConfig();
        vm.setName(getName());
        vm.item.set("temporary", true);
        vm.item.set("agent_mode", backend == VMBackend.QEMU);
        vm.item.set("backend", backend);
        vm.item.set("hypervisor", hypervisor);
        vm.item.set("cpu_count", 1);
        // The existing general-purpose initramfs expands to roughly 113 MiB. 320 MiB is the
        // measured reliable floor on TCG while keeping a useful margin for filesystem modules.
        vm.item.set("memory_mb", 320);
        vm.item.set("hugepages", false);
        vm.item.set("rng", false);
        vm.item.set("balloon", false);
        vm.item.set("usb", false);
        vm.item.set("audio_enabled", false);
        vm.item.set(LendMthpMode.KEY, LendMthpMode.DISABLED);
        var boot = BootConfig.of(vm);
        boot.setProtocol(BootConfig.Protocol.LINUX);
        boot.setLinuxSource(BootConfig.LinuxSource.MANUAL);
        boot.setKernel(PATH_BUILTIN_KERNEL);
        boot.setInitrd(PATH_BUILTIN_INITRD);
        // hvc0 is the private virtio-serial control terminal; ttyAMA0 remains the human-facing
        // boot log and rescue shell. The last console= owns /dev/console, hence the ordering.
        boot.setCmdline("console=ttyAMA0 console=hvc0 rdinit=/bin/sh panic=-1");
        var diskItems = DataItem.newArray();
        for (var disk : disks) {
            var item = DataItem.newObject();
            item.set("path", disk.getFullPath());
            item.set("bus", DiskBus.VIRTIO);
            diskItems.append(item);
        }
        vm.item.set("disks", diskItems);
        vm.item.set("networks", DataItem.newArray());
        return vm;
    }

    public void setActionVar(@NonNull String key, @NonNull String value) {
        vars.put(key, value);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean hasActionVar(@NonNull String key) {
        return vars.containsKey(key);
    }

    @Nullable
    public String getActionVar(@NonNull String key, @Nullable String def) {
        var val = vars.getOrDefault(key, def);
        if (val == null || val.isEmpty()) return def;
        return val;
    }

    public void clearActionVar(@NonNull String key) {
        vars.remove(key);
    }
}
