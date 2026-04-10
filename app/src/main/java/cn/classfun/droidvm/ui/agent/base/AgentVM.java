package cn.classfun.droidvm.ui.agent.base;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_KERNEL;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellRemoveTree;
import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.SharedDirType;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.utils.FileUtils;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class AgentVM implements JSONSerialize {
    private final static String AGENT_DIR = pathJoin(DATA_DIR, "/usr/share/droidvm/agent");
    private List<DiskConfig> disks = new ArrayList<>();
    private Map<String, String> vars = new HashMap<>();
    private Map<String, String> result = null;
    private String randomId = null;

    public AgentVM() {
    }

    public AgentVM(@NonNull DiskStore store, @NonNull JSONObject jo) throws JSONException {
        if (jo.has("id"))
            randomId = jo.getString("id");
        if (jo.has("disks")) this.disks = JsonUtils.arrayToList(jo, "disks", v -> {
            var disk = store.findById((String) v);
            if (disk == null) throw new JSONException(fmt(
                "Disk with id %s not found", v
            ));
            return disk;
        });
        if (jo.has("vars"))
            this.vars = JsonUtils.objectToStringMap(jo, "vars");
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var jo = new JSONObject();
        if (randomId != null)
            jo.put("id", randomId);
        var disksArr = new JSONArray();
        for (var disk : disks)
            disksArr.put(disk.getId().toString());
        jo.put("disks", disksArr);
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

    @NonNull
    private String getVarsDir() {
        return pathJoin(DATA_DIR, "run", getName());
    }

    public void addDisk(@NonNull DiskConfig disk) {
        disks.add(disk);
    }

    @NonNull
    public VMConfig buildVM() {
        var vm = new VMConfig();
        vm.setName(getName());
        vm.item.set("temporary", true);
        vm.item.set("cpu_count", 1);
        vm.item.set("memory_mb", 384);
        vm.item.set("kernel", PATH_BUILTIN_KERNEL);
        vm.item.set("initrd", PATH_BUILTIN_INITRD);
        vm.item.set("cmdline", "rd.systemd.unit=host-agent.target");
        var diskItems = DataItem.newArray();
        for (var disk : disks) {
            var item = DataItem.newObject();
            item.set("path", disk.getFullPath());
            item.set("bus", DiskBus.VIRTIO);
            diskItems.append(item);
        }
        vm.item.set("disks", diskItems);
        var dirItems = DataItem.newArray();
        var hostDir = DataItem.newObject();
        hostDir.set("path", AGENT_DIR);
        hostDir.set("tag", "host");
        hostDir.set("type", SharedDirType.FS);
        dirItems.append(hostDir);
        var varsDir = DataItem.newObject();
        varsDir.set("path", getVarsDir());
        varsDir.set("tag", "vars");
        varsDir.set("type", SharedDirType.FS);
        dirItems.append(varsDir);
        vm.item.set("shared_dirs", dirItems);
        return vm;
    }

    public void setActionVar(@NonNull String key, @NonNull String value) {
        vars.put(key, value);
    }

    @Nullable
    public String getActionVar(@NonNull String key, @Nullable String def) {
        var val = vars.getOrDefault(key, def);
        if (val == null || val.isEmpty()) return def;
        return val;
    }

    public void cleanupVars() {
        shellRemoveTree(getVarsDir());
    }

    public void prepareVars() throws IOException {
        var varsDir = getVarsDir();
        if (!new File(varsDir).mkdirs())
            throw new IOException("Failed to create vars dir");
        var sb = new StringBuilder();
        vars.forEach((k, v) -> sb.append(fmt("%s=%s\n", k, escapedString(v))));
        var actionFile = pathJoin(varsDir, "actions.txt");
        FileUtils.writeFile(actionFile, sb.toString());
    }

    @NonNull
    private Map<String, String> readResult() throws IOException {
        var resultFile = pathJoin(getVarsDir(), "result.txt");
        var result = new HashMap<String, String>();
        if (!new File(resultFile).exists())
            throw new IOException("Result file does not exist");
        var lines = FileUtils.readFile(resultFile);
        for (var line : lines.split("\n")) {
            var idx = line.indexOf('=');
            if (idx <= 0) continue;
            var key = line.substring(0, idx).trim();
            var value = line.substring(idx + 1).trim();
            result.put(key, value);
        }
        return result;
    }

    @NonNull
    private Map<String, String> getResult() {
        if (result == null) {
            try {
                result = readResult();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read result", e);
            }
        }
        return result;
    }

    @NonNull
    public String getResultItem(@NonNull String key, @NonNull String def) {
        var res = getResult();
        if (!res.containsKey(key)) return def;
        var val = res.getOrDefault(key, def);
        if (val == null || val.isEmpty()) return def;
        return val;
    }

    public boolean isResultValue(@NonNull String key, @NonNull String expected) {
        var val = getResultItem(key, "");
        return val.equals(expected);
    }
}
