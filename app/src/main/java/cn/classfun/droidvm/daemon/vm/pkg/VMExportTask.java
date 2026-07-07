package cn.classfun.droidvm.daemon.vm.pkg;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.daemon.vm.pkg.VMExportUtils.buildHeader;
import static cn.classfun.droidvm.daemon.vm.pkg.VMExportUtils.collectBootFiles;
import static cn.classfun.droidvm.daemon.vm.pkg.VMExportUtils.collectNetworks;
import static cn.classfun.droidvm.daemon.vm.pkg.VMExportUtils.sanitizeVM;
import static cn.classfun.droidvm.lib.archive.TarWriter.wrapCompressionOutput;
import static cn.classfun.droidvm.lib.pkg.PackageConstants.BUFFER;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.alignUp;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.alignUpStrict;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.writeZero;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.UUID;

import cn.classfun.droidvm.daemon.server.Server;
import cn.classfun.droidvm.lib.archive.RandomAccessFileOutputStream;
import cn.classfun.droidvm.lib.archive.TarWriter;
import cn.classfun.droidvm.lib.pkg.DiskEntry;
import cn.classfun.droidvm.lib.pkg.DiskRef;
import cn.classfun.droidvm.lib.pkg.PackageConstants;
import cn.classfun.droidvm.lib.pkg.PackageManifest;
import cn.classfun.droidvm.lib.pkg.Phase;
import cn.classfun.droidvm.lib.store.base.DataItem;

public final class VMExportTask {
    private static final String TAG = "VMExportTask";
    private static final long PACK_PROGRESS_INTERVAL_MS = 500;
    public final UUID taskId = UUID.randomUUID();
    private final Server server;
    private final PackageManifest manifest = new PackageManifest();
    private final UUID sourceVMId;
    private final int totalItems;
    private final String destPath;

    public VMExportTask(
        @NonNull Server server,
        @NonNull JSONObject request
    ) {
        this.server = server;
        destPath = request.optString("dest_path");
        if (!destPath.startsWith("/"))
            throw new IllegalArgumentException("missing dest_path");
        var vmId = UUID.fromString(request.optString("vm_id"));
        sourceVMId = vmId;
        var vm = server.getContext().getVMs().findById(vmId);
        if (vm == null) throw new IllegalArgumentException("target vm not found");
        manifest.vm = sanitizeVM(vm);
        manifest.compression = optEnum(
            request, "compression",
            PackageConstants.DEFAULT_COMPRESSION
        );
        var wanted = request.optJSONArray("disks");
        var wantedSet = new HashSet<Integer>();
        if (wanted != null) for (int i = 0; i < wanted.length(); i++)
            wantedSet.add(wanted.optInt(i, -1));
        var arr = vm.item.opt("disks", DataItem.newArray());
        for (int i = 0; i < arr.size(); i++) {
            var e = arr.get(i);
            if (!e.is(DataItem.Type.OBJECT)) continue;
            if (!wantedSet.isEmpty() && !wantedSet.contains(i)) continue;
            var disk = new DiskEntry(new DiskRef(i, e));
            disk.build();
            manifest.disks.add(disk);
        }
        collectBootFiles(manifest);
        collectNetworks(manifest, server.getContext().getNetworks(), vm);
        manifest.vm.item.remove("disks");
        totalItems = manifest.disks.size() + manifest.boots.size();
    }

    public void startAsync() {
        runOnPool(() -> {
            var data = DataItem.newObject();
            data.set("total", totalItems);
            try {
                startWrite();
                data.set("done", totalItems);
                emit(data, Phase.DONE);
            } catch (Exception e) {
                Log.w(TAG, fmt("Export task %s failed", taskId), e);
                data.set("done", 0);
                data.set("message", e.getMessage());
                emit(data, Phase.ERROR);
            } finally {
                var store = server.getContext().getExportTaskStore();
                store.remove(taskId);
            }
        });
    }

    private void startWrite() throws Exception {
        var outFile = new File(destPath);
        var manifestBytes = manifest.toJson().toString(4).getBytes(UTF_8);
        if (manifestBytes.length > 0xffff)
            throw new IOException(fmt("manifest too large: %d bytes", manifestBytes.length));
        try (var raf = new RandomAccessFile(outFile, "rw")) {
            raf.setLength(0);
            raf.write(new byte[PackageConstants.HEADER_SIZE]);
            writeZero(raf, alignUp(raf.getFilePointer()) - raf.getFilePointer());
            raf.write(manifestBytes);
            writeZero(raf, alignUpStrict(raf.getFilePointer()) - raf.getFilePointer());
            long dataStart = raf.getFilePointer();
            try (
                var fileOut = new RandomAccessFileOutputStream(raf);
                var compressed = wrapCompressionOutput(fileOut, manifest.compression);
                var tar = new TarWriter(compressed)
            ) {
                tar.entry(PackageConstants.MANIFEST_NAME, manifestBytes);
                pack(tar);
            }
            long dataEnd = raf.getFilePointer();
            long dataSize = dataEnd - dataStart;
            writeZero(raf, alignUp(dataEnd) - dataEnd);
            raf.seek(0);
            raf.write(buildHeader(manifest, manifestBytes.length, dataSize));
        }
    }

    private void pack(@NonNull TarWriter tar) throws Exception {
        int done;
        var data = DataItem.newObject();
        data.set("done", 0);
        if (!manifest.disks.isEmpty()) {
            data.set("total",  manifest.disks.size());
            emit(data, Phase.PACK);
            done = 0;
            for (var m : manifest.disks) {
                writeFile(
                    tar, m.archivePath, m.ref.path,
                    done, manifest.disks.size()
                );
                done++;
            }
        }
        if (!manifest.boots.isEmpty()) {
            data.set("total", manifest.boots.size());
            emit(data, Phase.PACK);
            done = 0;
            for (var bf : manifest.boots) {
                writeFile(
                    tar, bf.archivePath, bf.path,
                    done, manifest.boots.size()
                );
                done++;
            }
        }
    }

    private void writeFile(
        @NonNull TarWriter tar,
        @NonNull String item,
        @NonNull String path,
        int doneItems,
        int totalItems
    ) throws Exception {
        var f = new File(path);
        var size = f.length();
        var data = DataItem.newObject();
        data.set("done", doneItems);
        data.set("total", totalItems);
        data.set("file", basename(path));
        data.set("bytes_done", 0);
        data.set("bytes_total", size);
        emit(data, Phase.PACK);
        tar.entry(item, size, os -> {
            try (var is = new FileInputStream(f)) {
                var buf = new byte[BUFFER];
                long written = 0;
                long lastEmit = System.currentTimeMillis();
                int r;
                while ((r = is.read(buf)) > 0) {
                    os.write(buf, 0, r);
                    written += r;
                    var now = System.currentTimeMillis();
                    if (now - lastEmit >= PACK_PROGRESS_INTERVAL_MS) {
                        data.set("bytes_done", written);
                        emit(data, Phase.PACK);
                        lastEmit = now;
                    }
                }
                data.set("bytes_done", size);
                data.set("done", doneItems + 1);
                emit(data, Phase.PACK);
            }
        });
    }

    private void emit(@NonNull DataItem item, @NonNull Phase phase) {
        try {
            var data = item.toJson();
            data.put("event", "vm_export_status");
            data.put("task_id", taskId.toString());
            data.put("phase", phase.name().toLowerCase());
            data.put("vm_id", sourceVMId.toString());
            data.put("vm_name", manifest.vm.getName());
            var ev = new JSONObject();
            ev.put("type", "event");
            ev.put("data", data);
            server.broadcastEvent(ev);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to emit export event for %s", taskId), e);
        }
    }
}
