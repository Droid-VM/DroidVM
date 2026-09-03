// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

public final class VmDiskSharingTest {
    /**
     * Slots are written {@code "/path"} for a writable virtio one, {@code "/path:ro"} for one
     * attached read-only, and {@code "/path:cd"} for a CDROM-bus one whose flag says writable.
     */
    private static VMConfig vm(String name, String... slots) {
        var config = new VMConfig();
        config.setName(name);
        var disks = DataItem.newArray();
        for (var slot : slots) {
            boolean readonly = slot.endsWith(":ro");
            boolean cdrom = slot.endsWith(":cd");
            var disk = DataItem.newObject();
            disk.set("path", readonly || cdrom ? slot.substring(0, slot.length() - 3) : slot);
            disk.set("readonly", readonly);
            disk.set("bus", cdrom ? DiskBus.CDROM : DiskBus.VIRTIO);
            disks.append(disk);
        }
        config.item.set("disks", disks);
        return config;
    }

    private static VMStore store(VMConfig... vms) {
        var store = new VMStore();
        for (var vm : vms) store.add(vm);
        return store;
    }

    /** An empty path is a slot the user has not filled in yet, never a file. */
    @Test
    public void attachedPathsSkipEmptySlotsAndOptionallyTheUnwritableOnes() {
        var config = vm("a", "/data.qcow2", "/base.qcow2:ro", "/install.iso:cd", "");
        assertEquals(List.of("/data.qcow2", "/base.qcow2", "/install.iso"),
            new ArrayList<>(VmDiskSharing.attachedPaths(config, false)));
        assertEquals("the CDROM bus is read-only in both backends, flag or no flag",
            List.of("/data.qcow2"),
            new ArrayList<>(VmDiskSharing.attachedPaths(config, true)));
    }

    @Test
    public void othersAttachmentsCountReadOnlyOnesAndSkipTheExcludedVm() {
        var self = vm("self", "/shared.qcow2", "/own.qcow2");
        var other = vm("other", "/shared.qcow2:ro", "/theirs.qcow2");
        var vms = store(self, other);

        var others = VmDiskSharing.pathsAttachedByOthers(vms, self.getId());
        assertTrue("a read-only attachment still holds the file",
            others.contains("/shared.qcow2"));
        assertTrue(others.contains("/theirs.qcow2"));
        assertFalse("the VM's own slots are not someone else's", others.contains("/own.qcow2"));

        assertTrue("no exclusion means every VM counts",
            VmDiskSharing.pathsAttachedByOthers(vms, null).contains("/own.qcow2"));
    }

    @Test
    public void sharersNameEveryOtherVmOnThePathAndOmitUnsharedOnes() {
        var self = vm("self", "/shared.qcow2", "/own.qcow2");
        var vms = store(self, vm("b", "/shared.qcow2"), vm("c", "/shared.qcow2:ro"));

        var sharers = VmDiskSharing.sharersOf(
            vms, self.getId(), VmDiskSharing.attachedPaths(self, true));

        assertEquals(List.of("/shared.qcow2"), new ArrayList<>(sharers.keySet()));
        assertEquals(List.of("b", "c"), sharers.get("/shared.qcow2"));
    }

    @Test
    public void nothingIsSharedWhenThisIsTheOnlyVm() {
        var self = vm("self", "/own.qcow2");
        assertTrue(VmDiskSharing.sharersOf(store(self), self.getId(),
            VmDiskSharing.attachedPaths(self, true)).isEmpty());
    }

    /** The rule the delete dialog offers and {@code VMDeletion} then enforces. */
    @Test
    public void deletionOffersOnlyWritableDisksNoOneElseReferences() {
        var doomed = vm("doomed", "/shared.qcow2", "/own.qcow2", "/install.iso:cd");
        var vms = store(doomed, vm("keeper", "/shared.qcow2:ro"));

        var dangling = VmDiskSharing.attachedPaths(doomed, true);
        dangling.removeAll(VmDiskSharing.pathsAttachedByOthers(vms, doomed.getId()));

        assertEquals(List.of("/own.qcow2"), new ArrayList<>(dangling));
    }
}
