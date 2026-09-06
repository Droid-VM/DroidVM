// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.pkg;

import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

import cn.classfun.droidvm.lib.pkg.BootFile;
import cn.classfun.droidvm.lib.pkg.DiskEntry;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

public final class VMImportUtils {
    private VMImportUtils() {
    }

    /**
     * Rebuild the VM's disk list from the files the import placed. Only entries that fill a disk
     * slot go in: a backing image is on disk for the overlay above it to read, not for the guest
     * to see. Slot order comes from the manifest rather than from the order the tar happened to
     * store the files in, which no longer matches once a chain is interleaved with its disks.
     */
    public static void remapDiskPaths(
        @NonNull VMConfig vm,
        @NonNull ArrayList<DiskEntry> placed
    ) {
        var attached = new ArrayList<DiskEntry>();
        for (var disk : placed) {
            if (disk.target == null || disk.ref == null || !disk.attached) continue;
            attached.add(disk);
        }
        // Stable: packages written before the slot index existed carry 0 for every entry, and
        // keep the order they were packed in.
        attached.sort(Comparator.comparingInt((DiskEntry disk) -> disk.ref.index));
        var disks = DataItem.newArray();
        for (var disk : attached) {
            var item = DataItem.newObject();
            item.set("path", disk.target.getPath());
            item.set("readonly", disk.ref.readonly);
            item.set("bus", disk.ref.bus);
            disks.append(item);
        }
        vm.item.set("disks", disks);
    }

    public static void remapBootPaths(
        @NonNull VMConfig vm,
        @NonNull ArrayList<BootFile> placed
    ) {
        var byKind = new HashMap<String, String>();
        for (var boot : placed)
            byKind.put(boot.kind, boot.target.getPath());
        var boot = BootConfig.of(vm);
        if (byKind.containsKey("uefi"))
            boot.setUefiFirmware(requireNonNull(byKind.get("uefi")));
        if (byKind.containsKey("kernel"))
            boot.setKernel(requireNonNull(byKind.get("kernel")));
        if (byKind.containsKey("initrd"))
            boot.setInitrd(requireNonNull(byKind.get("initrd")));
    }

    @NonNull
    public static File uniqueFile(
        @NonNull File dir,
        @NonNull String name
    ) {
        var dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        var f = new File(dir, name);
        int i = 1;
        while (f.exists()) f = new File(dir, fmt("%s_%d%s", stem, i++, ext));
        return f;
    }
}
