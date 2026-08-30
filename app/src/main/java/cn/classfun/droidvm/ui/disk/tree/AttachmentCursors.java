// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.tree;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/** Builds the cursor set of one family from the stores plus, optionally, an editor's rows. */
public final class AttachmentCursors {
    /** The disk rows of a VM editor as they are right now, unsaved. */
    public static final class LiveRows {
        /** Null for a VM that has never been saved. */
        @Nullable
        public final UUID vmId;
        @NonNull
        public final String vmName;
        @NonNull
        public final List<String> paths;
        @NonNull
        public final List<Boolean> readonly;
        /** Index of the row the panel was opened from. */
        public final int active;

        public LiveRows(
            @Nullable UUID vmId,
            @NonNull String vmName,
            @NonNull List<String> paths,
            @NonNull List<Boolean> readonly,
            int active
        ) {
            this.vmId = vmId;
            this.vmName = vmName;
            this.paths = paths;
            this.readonly = readonly;
            this.active = active;
        }
    }

    private AttachmentCursors() {
    }

    /** Live rows first, then every persisted slot; see the two halves below. */
    @NonNull
    public static List<AttachmentCursor> collect(
        @NonNull DiskStore disks,
        @NonNull VMStore vms,
        @NonNull Set<UUID> family,
        @Nullable LiveRows live,
        @NonNull Collection<String> inUseVmNames
    ) {
        var out = new ArrayList<AttachmentCursor>();
        if (live != null) out.addAll(collectLive(disks, family, live, inUseVmNames));
        out.addAll(collectPersisted(disks, vms, family,
            live == null ? null : live.vmId, inUseVmNames));
        return out;
    }

    /** The editor's rows that point at a disk in {@code family}: one ACTIVE, the rest EDITOR. */
    @NonNull
    public static List<AttachmentCursor> collectLive(
        @NonNull DiskStore disks,
        @NonNull Set<UUID> family,
        @NonNull LiveRows live,
        @NonNull Collection<String> inUseVmNames
    ) {
        var out = new ArrayList<AttachmentCursor>();
        boolean pinned = live.vmId != null && inUseVmNames.contains(live.vmName);
        for (int i = 0; i < live.paths.size(); i++) {
            var cfg = disks.findByPath(live.paths.get(i));
            if (cfg == null || !family.contains(cfg.getId())) continue;
            var kind = i == live.active
                ? AttachmentCursor.Kind.ACTIVE : AttachmentCursor.Kind.EDITOR;
            out.add(new AttachmentCursor(kind, live.vmId, live.vmName, i, cfg.getId(),
                cfg.getFullPath(), live.readonly.get(i), pinned));
        }
        return out;
    }

    /**
     * Every saved slot pointing at a disk in {@code family}. Slots of {@code editingVmId} become
     * SHADOW (an editor's rows stand in for them); all others PERSISTED. {@code inUseVmNames}
     * marks cursors pinned.
     */
    @NonNull
    public static List<AttachmentCursor> collectPersisted(
        @NonNull DiskStore disks,
        @NonNull VMStore vms,
        @NonNull Set<UUID> family,
        @Nullable UUID editingVmId,
        @NonNull Collection<String> inUseVmNames
    ) {
        var out = new ArrayList<AttachmentCursor>();
        for (int v = 0; v < vms.size(); v++) {
            var vm = vms.get(v);
            var kind = editingVmId != null && editingVmId.equals(vm.getId())
                ? AttachmentCursor.Kind.SHADOW : AttachmentCursor.Kind.PERSISTED;
            boolean pinned = inUseVmNames.contains(vm.getName());
            var slots = diskSlots(vm);
            for (int i = 0; i < slots.size(); i++) {
                var slot = slots.get(i);
                var path = slot.optString("path", "");
                if (path.isEmpty()) continue;
                var cfg = disks.findByPath(path);
                if (cfg == null || !family.contains(cfg.getId())) continue;
                out.add(new AttachmentCursor(kind, vm.getId(), vm.getName(), i, cfg.getId(),
                    cfg.getFullPath(), slot.optBoolean("readonly", false), pinned));
            }
        }
        return out;
    }

    /** Names of all VMs in the store (plus {@code extra}), the candidates for one in-use query. */
    @NonNull
    public static List<String> allVmNames(@NonNull VMStore vms, @Nullable String extra) {
        var names = new ArrayList<String>();
        for (int i = 0; i < vms.size(); i++) names.add(vms.get(i).getName());
        if (extra != null && !extra.isEmpty() && !names.contains(extra)) names.add(extra);
        return names;
    }

    /** Distinct VM names among {@code cursors}, in first-seen order. */
    @NonNull
    public static List<String> vmNames(@NonNull Collection<AttachmentCursor> cursors) {
        var names = new LinkedHashSet<String>();
        for (var c : cursors) names.add(c.vmName);
        return new ArrayList<>(names);
    }

    /** Distinct names of the VMs whose cursors are pinned. */
    @NonNull
    public static List<String> pinnedVmNames(@NonNull Collection<AttachmentCursor> cursors) {
        var pinned = new ArrayList<AttachmentCursor>();
        for (var c : cursors) if (c.pinned) pinned.add(c);
        return vmNames(pinned);
    }

    @NonNull
    public static List<DataItem> diskSlots(@NonNull VMConfig vm) {
        var disks = vm.item.opt("disks", null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY)) return List.of();
        return disks.asArray();
    }
}
