package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * What a package has to carry for a set of VM disks. Every case here is a disk layout the app
 * can actually produce: branch management makes overlays of overlays, two VMs can share one base
 * image, and nothing stops two disks in different folders from having the same file name - the
 * case that quietly collided inside the tar before archive names were made unique.
 */
public class DiskChainPlanTest {
    /** A chain the walk reads from a map instead of from qcow2 headers. */
    private static DiskChainPlan.BackingLookup lookup(Map<String, String> parents) {
        return path -> parents.get(path);
    }

    private static DiskRef disk(int index, String path) {
        return new DiskRef(index, path);
    }

    @Nullable
    private static DiskChainPlan.Member find(List<DiskChainPlan.Member> plan, String path) {
        for (var member : plan)
            if (member.path.equals(path)) return member;
        return null;
    }

    private static List<String> archiveNames(List<DiskChainPlan.Member> plan) {
        var out = new ArrayList<String>();
        for (var member : plan) out.add(member.archivePath);
        return out;
    }

    @Test
    public void standaloneDiskIsItsOwnPlan() throws Exception {
        var plan = DiskChainPlan.build(
            List.of(disk(0, "/vm/root.img")), lookup(Map.of()));
        assertEquals(1, plan.size());
        assertEquals("root.img", plan.get(0).archivePath);
        assertEquals("", plan.get(0).backingArchive);
        assertNotEquals(null, plan.get(0).attachment);
    }

    @Test
    public void wholeChainIsPackedChildFirst() throws Exception {
        var plan = DiskChainPlan.build(
            List.of(disk(0, "/vm/top.qcow2")),
            lookup(Map.of(
                "/vm/top.qcow2", "/vm/mid.qcow2",
                "/vm/mid.qcow2", "/vm/base.qcow2"
            )));
        assertEquals(
            List.of("/vm/top.qcow2", "/vm/mid.qcow2", "/vm/base.qcow2"),
            List.of(plan.get(0).path, plan.get(1).path, plan.get(2).path));
        assertEquals("mid.qcow2", plan.get(0).backingArchive);
        assertEquals("base.qcow2", plan.get(1).backingArchive);
        assertEquals("", plan.get(2).backingArchive);
        // Only the disk the VM has in a slot is a disk; the rest are there to be read.
        assertNotEquals(null, plan.get(0).attachment);
        assertNull(plan.get(1).attachment);
        assertNull(plan.get(2).attachment);
    }

    @Test
    public void sharedBaseIsPackedOnce() throws Exception {
        var plan = DiskChainPlan.build(
            List.of(disk(0, "/vm/a.qcow2"), disk(1, "/vm/b.qcow2")),
            lookup(Map.of(
                "/vm/a.qcow2", "/vm/base.qcow2",
                "/vm/b.qcow2", "/vm/base.qcow2"
            )));
        assertEquals(3, plan.size());
        var base = find(plan, "/vm/base.qcow2");
        assertNotEquals(null, base);
        assertEquals("base.qcow2", find(plan, "/vm/a.qcow2").backingArchive);
        assertEquals("base.qcow2", find(plan, "/vm/b.qcow2").backingArchive);
    }

    @Test
    public void sameNameInDifferentFoldersGetsDistinctArchiveNames() throws Exception {
        var plan = DiskChainPlan.build(
            List.of(disk(0, "/a/disk.qcow2"), disk(1, "/b/disk.qcow2")),
            lookup(Map.of("/a/disk.qcow2", "/base/disk.qcow2")));
        var names = archiveNames(plan);
        assertEquals(3, names.size());
        assertEquals(names.size(), new LinkedHashSet<>(names).size());
        assertEquals("disk.qcow2", names.get(0));
        // The suffix goes before the extension, so the format stays readable from the name.
        assertTrue(names.contains("disk_1.qcow2"));
        assertTrue(names.contains("disk_2.qcow2"));
    }

    @Test
    public void aBaseImageThatIsAlsoADiskKeepsItsSlot() throws Exception {
        var plan = DiskChainPlan.build(
            List.of(disk(0, "/vm/top.qcow2"), disk(1, "/vm/base.qcow2")),
            lookup(Map.of("/vm/top.qcow2", "/vm/base.qcow2")));
        assertEquals(2, plan.size());
        var base = find(plan, "/vm/base.qcow2");
        assertNotEquals(null, base);
        assertEquals(1, base.attachment.index);
        // The overlay still points at that one entry rather than a second copy of the file.
        assertEquals(base.archivePath, find(plan, "/vm/top.qcow2").backingArchive);
    }

    @Test
    public void theSameFileInTwoSlotsKeepsBothDisks() throws Exception {
        var plan = DiskChainPlan.build(
            List.of(disk(0, "/vm/data.img"), disk(1, "/vm/data.img")),
            lookup(Map.of()));
        assertEquals(2, plan.size());
        assertEquals(0, plan.get(0).attachment.index);
        assertEquals(1, plan.get(1).attachment.index);
        assertNotEquals(plan.get(0).archivePath, plan.get(1).archivePath);
    }

    @Test
    public void aLookupFailurePropagates() {
        DiskChainPlan.BackingLookup broken = path -> {
            throw new IOException("missing backing image: /gone/base.qcow2");
        };
        var e = assertThrows(IOException.class, () -> DiskChainPlan.build(
            List.of(disk(0, "/vm/top.qcow2")), broken));
        assertTrue(e.getMessage().contains("/gone/base.qcow2"));
    }

    @Test
    public void aLoopIsRefusedRatherThanWalkedForever() {
        var e = assertThrows(IOException.class, () -> DiskChainPlan.build(
            List.of(disk(0, "/vm/a.qcow2")),
            lookup(Map.of(
                "/vm/a.qcow2", "/vm/b.qcow2",
                "/vm/b.qcow2", "/vm/a.qcow2"
            ))));
        assertTrue(e.getMessage().contains("loops"));
    }

    @Test
    public void aChainDeeperThanTheCapIsRefused() {
        var parents = new HashMap<String, String>();
        int depth = DiskChainPlan.MAX_CHAIN + 2;
        for (int i = 0; i < depth; i++)
            parents.put(fmt("/vm/d%d.qcow2", i), fmt("/vm/d%d.qcow2", i + 1));
        var e = assertThrows(IOException.class, () -> DiskChainPlan.build(
            List.of(disk(0, "/vm/d0.qcow2")), lookup(parents)));
        assertTrue(e.getMessage().contains("deeper"));
    }

    @Test
    public void anEmptyPathIsSkipped() throws Exception {
        var plan = DiskChainPlan.build(
            List.of(disk(0, ""), disk(1, "/vm/root.img")), lookup(Map.of()));
        assertEquals(1, plan.size());
        assertEquals("/vm/root.img", plan.get(0).path);
    }

    @Test
    public void everyMemberIsWalkedOnlyThroughTheLookup() throws Exception {
        var asked = new ArrayList<String>();
        var parents = Map.of("/vm/top.qcow2", "/vm/base.qcow2");
        var plan = DiskChainPlan.build(List.of(disk(0, "/vm/top.qcow2")), path -> {
            asked.add(path);
            return parents.get(path);
        });
        assertEquals(List.of("/vm/top.qcow2", "/vm/base.qcow2"), asked);
        assertSame(plan.get(0), find(plan, "/vm/top.qcow2"));
    }
}
