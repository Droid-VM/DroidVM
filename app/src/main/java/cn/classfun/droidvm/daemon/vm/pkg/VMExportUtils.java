// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.pkg;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_KERNEL;
import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;
import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_QEMU_FIRMWARE;
import static cn.classfun.droidvm.lib.Constants.PATH_MICRODROID_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_MICRODROID_INITRD_DEBUG;
import static cn.classfun.droidvm.lib.Constants.PATH_MICRODROID_KERNEL;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.putInt64LE;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.putUInt16LE;
import static cn.classfun.droidvm.lib.utils.FileUtils.canonicalPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.daemon.network.NetworkInstanceStore;
import cn.classfun.droidvm.lib.pkg.BootFile;
import cn.classfun.droidvm.lib.pkg.DiskChainPlan;
import cn.classfun.droidvm.lib.pkg.DiskEntry;
import cn.classfun.droidvm.lib.pkg.DiskRef;
import cn.classfun.droidvm.lib.pkg.PackageConstants;
import cn.classfun.droidvm.lib.pkg.PackageManifest;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.utils.ImageUtils;

public final class VMExportUtils {
    private static final String TAG = "VMExportUtils";
    private VMExportUtils() {
    }

    public static void scrubUnique(@NonNull DataItem item) {
        if (item.is(DataItem.Type.OBJECT)) {
            item.remove("id");
            item.remove("vm_id");
            item.remove("uuid");
            item.remove("mac");
            item.remove("mac_address");
            item.remove("tap_name");
            for (var child : item.asObject().values())
                scrubUnique(child);
        } else if (item.is(DataItem.Type.ARRAY)) {
            for (var child : item.asArray())
                scrubUnique(child);
        }
    }

    @NonNull
    public static byte[] buildHeader(
        @NonNull PackageManifest manifest,
        int manifestSize,
        long dataSize
    ) throws IOException {
        return buildHeader(manifest, manifestSize, dataSize, 0);
    }

    @NonNull
    public static byte[] buildHeader(
        @NonNull PackageManifest manifest,
        int manifestSize,
        long dataSize,
        int volumeCount
    ) throws IOException {
        var hdr = new byte[PackageConstants.HEADER_SIZE];
        var magic = PackageConstants.MAGIC.getBytes(UTF_8);
        System.arraycopy(magic, 0, hdr, 0, magic.length);
        putUInt16LE(hdr, 6, manifest.manifestVersion);
        putUInt16LE(hdr, 8, BuildConfig.VERSION_CODE);
        putUInt16LE(hdr, 10, manifestSize);
        putUInt16LE(hdr, 12, manifest.compression.type);
        putUInt16LE(hdr, 14, volumeCount);
        putInt64LE(hdr, 16, dataSize);
        return hdr;
    }

    private static void addBootFile(
        @NonNull PackageManifest manifest,
        @NonNull String path,
        @NonNull String kind,
        @NonNull Set<String> builtins,
        @NonNull Set<String> seen
    ) {
        if (path.isEmpty()) return;
        if (builtins.contains(path)) return;
        var file = new BootFile();
        file.path = path;
        file.kind = kind;
        if (!file.isExists()) return;
        file.build();
        if (!seen.add(file.archivePath)) return;
        manifest.boots.add(file);
    }

    /**
     * The files the package must carry for the chosen disks: each selected VM disk plus, when it
     * is a qcow2 overlay, every backing image under it. Packing the overlay alone was the whole
     * bug - the guest reads the base too, and the copied header points at a path that only ever
     * existed on the exporting phone.
     *
     * <p>Reads the source images (headers only) and never writes to them; the exporting phone's
     * disks and their registered parent links come out of an export exactly as they went in.
     *
     * @param wanted VM disk indices to include; empty means every disk.
     */
    public static void collectDisks(
        @NonNull PackageManifest manifest,
        @NonNull VMConfig vm,
        @NonNull Set<Integer> wanted
    ) throws Exception {
        var tops = new ArrayList<DiskRef>();
        var disks = vm.item.opt("disks", DataItem.newArray());
        for (int i = 0; i < disks.size(); i++) {
            var item = disks.get(i);
            if (!item.is(DataItem.Type.OBJECT)) continue;
            if (!wanted.isEmpty() && !wanted.contains(i)) continue;
            var ref = new DiskRef(i, item);
            if (ref.path.isEmpty()) continue;
            // Canonical so that a base image reached both as a disk and as another disk's
            // parent is recognised as the same file and packed once.
            ref.path = canonicalPath(ref.path);
            tops.add(ref);
        }
        for (var member : DiskChainPlan.build(tops, ImageUtils::backingOf))
            manifest.disks.add(DiskEntry.of(member));
    }

    public static void collectBootFiles(@NonNull PackageManifest manifest) {
        var builtins = new HashSet<String>();
        builtins.add(PATH_BUILTIN_KERNEL);
        builtins.add(PATH_BUILTIN_INITRD);
        builtins.add(PATH_EDK2_FIRMWARE);
        builtins.add(PATH_EDK2_QEMU_FIRMWARE);
        builtins.add(PATH_MICRODROID_KERNEL);
        builtins.add(PATH_MICRODROID_INITRD);
        builtins.add(PATH_MICRODROID_INITRD_DEBUG);
        var seen = new LinkedHashSet<String>();
        var boot = BootConfig.of(manifest.vm);
        if (boot.getProtocol() == BootConfig.Protocol.UEFI) {
            addBootFile(manifest, boot.getUefiFirmware(), "uefi", builtins, seen);
        } else if (boot.getLinuxSource() == BootConfig.LinuxSource.MANUAL) {
            addBootFile(manifest, boot.getKernel(), "kernel", builtins, seen);
            addBootFile(manifest, boot.getInitrd(), "initrd", builtins, seen);
        }
    }

    @NonNull
    public static VMConfig sanitizeVM(@NonNull VMConfig vm) {
        try {
            var copy = new VMConfig(vm.toJson());
            copy.item.remove("id");
            var nets = copy.item.opt("networks", null);
            if (nets != null && nets.is(DataItem.Type.ARRAY)) {
                var refMap = new HashMap<String, String>();
                int n = 0;
                for (var item : nets.asArray()) {
                    if (!item.is(DataItem.Type.OBJECT)) continue;
                    var original = item.optString("network_id", "");
                    if (!original.isEmpty()) {
                        var ref = refMap.get(original);
                        if (ref == null) {
                            ref = fmt("net%d", n++);
                            refMap.put(original, ref);
                        }
                        item.set("pkg_network_ref", ref);
                    }
                    item.remove("network_id");
                    item.remove("mac_address");
                    item.remove("tap_name");
                }
            }
            return copy;
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to sanitize VM config", e);
        }
    }

    public static void collectNetworks(
        @NonNull PackageManifest manifest,
        @NonNull NetworkInstanceStore store,
        @NonNull VMConfig sourceVM
    ) {
        var srcNets = sourceVM.item.opt("networks", null);
        var exportNets = manifest.vm.item.opt("networks", null);
        if (srcNets == null || exportNets == null) return;
        if (!srcNets.is(DataItem.Type.ARRAY) || !exportNets.is(DataItem.Type.ARRAY)) return;
        var seen = new HashSet<String>();
        var srcArr = srcNets.asArray();
        var exportArr = exportNets.asArray();
        for (int i = 0; i < srcArr.size() && i < exportArr.size(); i++) {
            var src = srcArr.get(i);
            var exported = exportArr.get(i);
            if (!src.is(DataItem.Type.OBJECT) || !exported.is(DataItem.Type.OBJECT)) continue;
            var netId = src.optString("network_id", "");
            var ref = exported.optString("pkg_network_ref", "");
            if (netId.isEmpty() || ref.isEmpty() || !seen.add(netId)) continue;
            var net = store.findById(netId);
            if (net == null) continue;
            try {
                var cfg = new NetworkConfig(net.toJson());
                scrubUnique(cfg.item);
                cfg.item.set("pkg_network_ref", ref);
                manifest.networks.add(cfg);
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to export network %s", netId), e);
            }
        }
    }
}
