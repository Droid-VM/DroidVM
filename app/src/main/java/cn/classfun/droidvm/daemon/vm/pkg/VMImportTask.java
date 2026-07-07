package cn.classfun.droidvm.daemon.vm.pkg;

import static cn.classfun.droidvm.daemon.vm.pkg.VMImportUtils.remapBootPaths;
import static cn.classfun.droidvm.daemon.vm.pkg.VMImportUtils.remapDiskPaths;
import static cn.classfun.droidvm.daemon.vm.pkg.VMImportUtils.uniqueFile;
import static cn.classfun.droidvm.lib.pkg.PackageConstants.BUFFER;
import static cn.classfun.droidvm.lib.utils.JsonUtils.listToJSONArray;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.daemon.server.Server;
import cn.classfun.droidvm.lib.archive.TarReader;
import cn.classfun.droidvm.lib.pkg.BootFile;
import cn.classfun.droidvm.lib.pkg.DiskEntry;
import cn.classfun.droidvm.lib.pkg.PackageInput;
import cn.classfun.droidvm.lib.pkg.PackageManifest;
import cn.classfun.droidvm.lib.pkg.Phase;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

public final class VMImportTask {
    private static final String TAG = "VMImportTask";
    private static final long PROGRESS_INTERVAL_MS = 500;
    public final UUID taskId = UUID.randomUUID();
    private final Server server;
    private final String srcPath;
    private final File targetDir;
    private final String networkMode;
    private int totalItems;
    public VMConfig importedVM = null;
    public PackageManifest importedManifest = null;
    public final ArrayList<DiskEntry> placedDisks = new ArrayList<>();
    public final ArrayList<BootFile> placedBoots = new ArrayList<>();

    public VMImportTask(@NonNull Server server, @NonNull JSONObject request) {
        this.server = server;
        srcPath = request.optString("src_path", "");
        if (!srcPath.startsWith("/"))
            throw new IllegalArgumentException("missing src_path");
        var targetPath = request.optString("target_dir", "");
        if (!targetPath.startsWith("/"))
            throw new IllegalArgumentException("missing target_dir");
        targetDir = new File(targetPath);
        networkMode = request.optString("network_mode", "auto");
    }

    public void startAsync() {
        runOnPool(this::run);
    }

    private void run() {
        var data = DataItem.newObject();
        try {
            unpack();
            importedVM.setName(uniqueVMName(importedVM.getName()));
            var networks = importNetworks(importedVM, importedManifest.networks);
            var vmId = server.getContext().getVMs().createVM(importedVM);
            if (vmId == null || vmId.isEmpty()) throw new IOException("failed to register VM");
            data.set("done", totalItems);
            data.set("total", totalItems);
            data.set("vm_id", vmId);
            data.set("vm_name", importedVM.getName());
            data.set("disks", listToJSONArray(placedDisks));
            data.set("boots", listToJSONArray(placedBoots));
            data.set("networks", networks);
            emit(data, Phase.DONE);
        } catch (Exception e) {
            Log.w(TAG, fmt("Import task %s failed", taskId), e);
            data.set("done", 0);
            data.set("total", totalItems);
            data.set("message", e.getMessage());
            emit(data, Phase.ERROR);
        } finally {
            server.getContext().getImportTaskStore().remove(taskId);
        }
    }

    private void onTarItem(
        @NonNull String name,
        int mode,
        long size,
        @NonNull InputStream content
    ) throws Exception{
        var disk = importedManifest.findDisk(name);
        if (disk != null) {
            var target = uniqueFile(targetDir, disk.name);
            copyEntry(content, target, size, placedDisks.size(), totalItems);
            disk.target = target;
            placedDisks.add(disk);
            return;
        }
        var boot = importedManifest.findBoot(name);
        if (boot != null) {
            var dir = new File(targetDir, "boot");
            if (!dir.exists() && !dir.mkdirs())
                throw new IOException(fmt("Cannot create %s", dir));
            var target = uniqueFile(dir, boot.name);
            var count = placedDisks.size() + placedBoots.size();
            copyEntry(content, target, size, count, totalItems);
            boot.target = target;
            placedBoots.add(boot);
        }
    }

    private void unpack() throws Exception {
        if (!targetDir.exists() && !targetDir.mkdirs())
            throw new IOException(fmt("Cannot create target dir: %s", targetDir));
        try (
            var in = new FileInputStream(srcPath);
            var pkg = PackageInput.open(in)
        ) {
            importedManifest = pkg.manifest;
            totalItems = importedManifest.disks.size() + importedManifest.boots.size();
            var data = DataItem.newObject();
            data.set("done", 0);
            data.set("total", totalItems);
            emit(data, Phase.PACK);
            var tar = new TarReader(pkg.data);
            tar.forEach(this::onTarItem);
            var buf = new byte[BUFFER];
            //noinspection StatementWithEmptyBody
            while (pkg.data.read(buf) >= 0);
            pkg.validateDataConsumed();
        }
        var vm = new VMConfig(importedManifest.vm.toJson());
        vm.setId(UUID.randomUUID());
        remapDiskPaths(vm, placedDisks);
        remapBootPaths(vm, placedBoots);
        importedVM = vm;
    }

    @NonNull
    private JSONArray importNetworks(
        @NonNull VMConfig vm,
        @NonNull List<NetworkConfig> configs
    ) throws Exception {
        var refs = new HashMap<String, String>();
        var created = new JSONArray();
        if (networkMode.equals("skip")) {
            remapNetworks(vm, refs);
            return created;
        }
        var store = server.getContext().getNetworks();
        for (var source : configs) {
            var ref = source.item.optString("pkg_network_ref", "");
            if (ref.isEmpty()) continue;
            if (networkMode.equals("existing")) {
                var existing = store.findByName(source.getName());
                if (existing != null) refs.put(ref, existing.getId().toString());
                continue;
            }
            var cfg = new NetworkConfig(source.toJson());
            cfg.item.remove("pkg_network_ref");
            cfg.setId(UUID.randomUUID());
            cfg.setName(uniqueNetworkName(cfg.getName()));
            makeBridgeNameUnique(cfg);
            var id = store.createNetwork(cfg);
            if (id == null || id.isEmpty()) continue;
            refs.put(ref, id);
            created.put(cfg.toJson());
        }
        remapNetworks(vm, refs);
        return created;
    }

    private void remapNetworks(
        @NonNull VMConfig vm,
        @NonNull HashMap<String, String> refs
    ) {
        var nets = vm.item.opt("networks", null);
        if (nets == null || !nets.is(DataItem.Type.ARRAY)) return;
        for (var nic : nets.asArray()) {
            if (!nic.is(DataItem.Type.OBJECT)) continue;
            var ref = nic.optString("pkg_network_ref", "");
            nic.remove("pkg_network_ref");
            var id = refs.get(ref);
            if (id == null || id.isEmpty()) nic.remove("network_id");
            else nic.set("network_id", id);
        }
    }

    @NonNull
    private String uniqueNetworkName(@NonNull String name) {
        var store = server.getContext().getNetworks();
        if (store.findByName(name) == null) return name;
        int i = 1;
        while (store.findByName(fmt("%s_%d", name, i)) != null) i++;
        return fmt("%s_%d", name, i);
    }

    private void makeBridgeNameUnique(@NonNull NetworkConfig cfg) {
        var bridge = cfg.getBridgeName();
        if (bridge == null || bridge.isEmpty()) return;
        if (isBridgeNameUnique(bridge)) return;
        int i = 1;
        while (!isBridgeNameUnique(fmt("%s%d", bridge, i))) i++;
        cfg.setBridgeName(fmt("%s%d", bridge, i));
    }

    private boolean isBridgeNameUnique(@NonNull String bridgeName) {
        var unique = new AtomicBoolean(true);
        server.getContext().getNetworks().forEach((id, net) -> {
            if (bridgeName.equals(net.getBridgeName())) unique.set(false);
        });
        return unique.get();
    }

    private void copyEntry(
        @NonNull InputStream in,
        @NonNull File out,
        long size,
        int doneItems,
        int totalItems
    ) throws IOException {
        var data = DataItem.newObject();
        data.set("done", doneItems);
        data.set("total", totalItems);
        data.set("file", basename(out.getPath()));
        data.set("bytes_done", 0);
        data.set("bytes_total", size);
        emit(data, Phase.PACK);
        try (var os = new FileOutputStream(out)) {
            var buf = new byte[BUFFER];
            long rem = size;
            long written = 0;
            long lastEmit = System.currentTimeMillis();
            while (rem > 0) {
                int toRead = (int) Math.min(buf.length, rem);
                int n = in.read(buf, 0, toRead);
                if (n <= 0) throw new IOException(fmt(
                    "entry %s: short read", out.getName()
                ));
                os.write(buf, 0, n);
                rem -= n;
                written += n;
                var now = System.currentTimeMillis();
                if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                    data.set("bytes_done", written);
                    emit(data, Phase.PACK);
                    lastEmit = now;
                }
            }
        }
        data.set("done", doneItems + 1);
        data.set("bytes_done", size);
        emit(data, Phase.PACK);
    }

    @NonNull
    private String uniqueVMName(@NonNull String name) {
        var store = server.getContext().getVMs();
        if (store.findByName(name) == null) return name;
        int i = 1;
        while (store.findByName(fmt("%s (%d)", name, i)) != null) i++;
        return fmt("%s (%d)", name, i);
    }

    private void emit(@NonNull DataItem item, @NonNull Phase phase) {
        try {
            var data = item.toJson();
            data.put("event", "vm_import_status");
            data.put("task_id", taskId.toString());
            data.put("phase", phase.name().toLowerCase());
            var ev = new JSONObject();
            ev.put("type", "event");
            ev.put("data", data);
            server.broadcastEvent(ev);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to emit import event for %s", taskId), e);
        }
    }
}
