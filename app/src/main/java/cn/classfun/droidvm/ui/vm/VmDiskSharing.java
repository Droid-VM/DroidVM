// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.enums.Enums;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.disk.tree.AttachmentCursors;

/**
 * Which VMs attach the same disk file. Two VMs writing one image corrupt it, and a reader under
 * a writer sees the file change underneath - but that only bites while the other VM actually
 * holds the file, so nothing here decides anything on its own: it answers "who else has this
 * path in a slot", and each caller pairs that with what it is about to do.
 *
 * <p>{@link VMActions} pairs it with the daemon's run state before a start - only a VM that is
 * not stopped forces this start's attachments read-only - and {@link VMDeletion} uses it to keep
 * a disk any remaining VM still references. The VM disk editor deliberately does not use it: a
 * slot saved writable stays writable, and the start guard decides when sharing matters.
 */
public final class VmDiskSharing {
    private VmDiskSharing() {
    }

    /**
     * The disk paths this VM attaches, in slot order.
     *
     * @param writableOnly skip the slots the guest cannot write - see {@link #isWritable}
     */
    @NonNull
    public static LinkedHashSet<String> attachedPaths(
        @NonNull VMConfig config,
        boolean writableOnly
    ) {
        var paths = new LinkedHashSet<String>();
        for (var slot : AttachmentCursors.diskSlots(config)) {
            var path = slot.optString("path", "");
            if (path.isEmpty()) continue;
            if (writableOnly && !isWritable(slot)) continue;
            paths.add(path);
        }
        return paths;
    }

    /**
     * Whether this slot can be written. Beyond the read-only flag, a CDROM-bus slot never can:
     * both backends open it read-only whatever the flag says (qemu {@code media=cdrom,
     * readonly=on}, crosvm {@code ro=true,type=cdrom}), so it neither risks the image nor counts
     * as a file this VM owns.
     */
    private static boolean isWritable(@NonNull DataItem slot) {
        return !slot.optBoolean("readonly", false)
            && Enums.optEnum(slot, "bus", DiskBus.VIRTIO) != DiskBus.CDROM;
    }

    /**
     * Every path attached by a VM other than {@code excludeVmId}, read-only attachments
     * included: a disk another VM reads is as much in use as one it writes.
     */
    @NonNull
    public static Set<String> pathsAttachedByOthers(
        @NonNull VMStore vms,
        @Nullable UUID excludeVmId
    ) {
        var paths = new LinkedHashSet<String>();
        for (int i = 0; i < vms.size(); i++) {
            var vm = vms.get(i);
            if (isExcluded(vm, excludeVmId)) continue;
            paths.addAll(attachedPaths(vm, false));
        }
        return paths;
    }

    /**
     * For each of {@code paths}, the names of the other VMs attaching it, in store order. Paths
     * nobody else attaches are absent, so an empty result means nothing is shared.
     */
    @NonNull
    public static Map<String, List<String>> sharersOf(
        @NonNull VMStore vms,
        @Nullable UUID excludeVmId,
        @NonNull Collection<String> paths
    ) {
        var out = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < vms.size(); i++) {
            var vm = vms.get(i);
            if (isExcluded(vm, excludeVmId)) continue;
            var attached = attachedPaths(vm, false);
            for (var path : paths) {
                if (!attached.contains(path)) continue;
                var names = out.computeIfAbsent(path, p -> new ArrayList<>());
                var name = vm.getName();
                if (name != null && !names.contains(name)) names.add(name);
            }
        }
        return out;
    }

    /** A store loaded from a hand-edited file can hold an entry with no usable id. */
    private static boolean isExcluded(@NonNull VMConfig vm, @Nullable UUID excludeVmId) {
        if (excludeVmId == null) return false;
        try {
            return excludeVmId.equals(vm.getId());
        } catch (Exception e) {
            return false;
        }
    }
}
