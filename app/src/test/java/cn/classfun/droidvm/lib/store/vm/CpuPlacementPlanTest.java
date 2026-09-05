package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.utils.CpuUtils;

public class CpuPlacementPlanTest {
    /**
     * crosvm separates per-vCPU assignments with ':' and uses ',' only inside one
     * assignment's host set. A comma-separated assignment list is what the flag
     * rejects as "invalid VCPU assignment syntax", so it must not round-trip.
     */
    @Test
    public void affinityUsesColonBetweenAssignments() {
        var parsed = CpuPlacementPlan.parseAffinity("0=0:1=1:6=6");
        assertEquals(List.of(0), parsed.get(0));
        assertEquals(List.of(1), parsed.get(1));
        assertEquals(List.of(6), parsed.get(6));
        assertEquals("0=0:1=1:6=6", CpuPlacementPlan.formatAffinity(parsed));
    }

    @Test
    public void affinityCommaBindsOneVcpuToManyHosts() {
        var parsed = CpuPlacementPlan.parseAffinity("0=4,5:1=6");
        assertEquals(List.of(4, 5), parsed.get(0));
        assertEquals(List.of(6), parsed.get(1));
    }

    @Test
    public void affinityAcceptsHostRanges() {
        var parsed = CpuPlacementPlan.parseAffinity("0=0-3");
        assertEquals(List.of(0, 1, 2, 3), parsed.get(0));
        // Ranges collapse back on the way out.
        assertEquals("0=0-3", CpuPlacementPlan.formatAffinity(parsed));
    }

    @Test
    public void affinityDropsMalformedAssignments() {
        var parsed = CpuPlacementPlan.parseAffinity("0=0:garbage:2=:3=3");
        assertEquals(2, parsed.size());
        assertTrue(parsed.containsKey(0));
        assertTrue(parsed.containsKey(3));
    }

    /** A hand-written global CPUSET has no per-vCPU info, so it parses to empty. */
    @Test
    public void affinityIgnoresGlobalCpusetForm() {
        assertTrue(CpuPlacementPlan.parseAffinity("0,1-3,5").isEmpty());
    }

    @Test
    public void capacityRoundTrips() {
        var parsed = CpuPlacementPlan.parseCapacity("0=792,6=1024");
        assertEquals(Long.valueOf(792), parsed.get(0));
        assertEquals(Long.valueOf(1024), parsed.get(6));
        assertEquals("0=792,6=1024", CpuPlacementPlan.formatCapacity(parsed));
    }

    /** A vCPU floating over two tiers gets the weaker core's capacity. */
    @Test
    public void deriveCapacityTakesWeakestPinnedCore() {
        var cores = fakeCores(new long[]{792, 792, 1024});
        var affinity = new TreeMap<Integer, List<Integer>>();
        affinity.put(0, List.of(2));
        affinity.put(1, List.of(0, 2));
        var capacity = CpuPlacementPlan.deriveCapacity(affinity, cores);
        assertEquals(Long.valueOf(1024), capacity.get(0));
        assertEquals(Long.valueOf(792), capacity.get(1));
    }

    @Test
    public void deriveClustersGroupsByCapacityWeakestFirst() {
        var capacity = new TreeMap<Integer, Long>();
        for (int i = 0; i < 6; i++) capacity.put(i, 792L);
        capacity.put(6, 1024L);
        var clusters = CpuPlacementPlan.deriveClusters(capacity, 7);
        assertEquals(2, clusters.size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), clusters.get(0));
        assertEquals(List.of(6), clusters.get(1));
        assertEquals("0-5;6", CpuPlacementPlan.formatClusters(clusters));
    }

    /**
     * crosvm builds the guest cpu-map all-or-nothing, so an unpinned vCPU must
     * still land in a cluster -- the weakest one, since it floats everywhere.
     */
    @Test
    public void deriveClustersCoversUnpinnedVcpus() {
        var capacity = new TreeMap<Integer, Long>();
        capacity.put(0, 792L);
        capacity.put(6, 1024L);
        var clusters = CpuPlacementPlan.deriveClusters(capacity, 7);
        assertEquals(List.of(0, 1, 2, 3, 4, 5), clusters.get(0));
        assertEquals(List.of(6), clusters.get(1));
    }

    /** The grouping the dialog's capacity summary renders, weakest tier first. */
    @Test
    public void groupByCapacityOrdersWeakestFirst() {
        var capacity = new TreeMap<Integer, Long>();
        capacity.put(0, 792L);
        capacity.put(6, 1024L);
        capacity.put(1, 792L);
        var grouped = CpuPlacementPlan.groupByCapacity(capacity);
        assertEquals(List.of(792L, 1024L), List.copyOf(grouped.keySet()));
        assertEquals(List.of(0, 1), grouped.get(792L));
        assertEquals(List.of(6), grouped.get(1024L));
    }

    @Test
    public void clusterOverlapIsReported() {
        var clusters = CpuPlacementPlan.parseClusters("0-3;3-5");
        assertEquals(List.of(3), CpuPlacementPlan.findClusterOverlaps(clusters));
    }

    @Test
    public void hostOverlapWithGpuCpusetIsReported() {
        var affinity = new TreeMap<Integer, List<Integer>>();
        affinity.put(0, List.of(6, 7));
        assertEquals(List.of(7), CpuPlacementPlan.findHostOverlaps(affinity, "7"));
        assertTrue(CpuPlacementPlan.findHostOverlaps(affinity, "5").isEmpty());
    }

    /** The user's 7-vCPU example, end to end. */
    @Test
    public void sevenVcpuBigLittleExample() {
        var cores = fakeCores(new long[]{792, 792, 792, 792, 792, 792, 1024});
        var affinity = new TreeMap<Integer, List<Integer>>();
        for (int i = 0; i < 7; i++) affinity.put(i, List.of(i));
        var capacity = CpuPlacementPlan.deriveCapacity(affinity, cores);
        var clusters = CpuPlacementPlan.deriveClusters(capacity, 7);
        assertEquals("0=0:1=1:2=2:3=3:4=4:5=5:6=6",
            CpuPlacementPlan.formatAffinity(affinity));
        assertEquals("0=792,1=792,2=792,3=792,4=792,5=792,6=1024",
            CpuPlacementPlan.formatCapacity(capacity));
        assertEquals("0-5;6", CpuPlacementPlan.formatClusters(clusters));
    }

    @Test
    public void parseCpuSetHandlesRangesAndJunk() {
        assertEquals(List.of(0, 1, 2, 3, 5), CpuUtils.parseCpuSet("0,1-3,5"));
        assertEquals(List.of(4), CpuUtils.parseCpuSet(" 4 , , x"));
        // Reversed range is dropped rather than throwing.
        assertTrue(CpuUtils.parseCpuSet("5-2").isEmpty());
    }

    /** CpuCore has no public constructor; build the list through the same math. */
    private static List<CpuUtils.CpuCore> fakeCores(long[] capacities) {
        var cores = new java.util.ArrayList<CpuUtils.CpuCore>();
        for (int i = 0; i < capacities.length; i++)
            cores.add(newCore(i, capacities[i]));
        return cores;
    }

    private static CpuUtils.CpuCore newCore(int index, long capacity) {
        try {
            var ctor = CpuUtils.CpuCore.class.getDeclaredConstructor(
                int.class, long.class, int.class, boolean.class, long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(index, 0L, 0, false, capacity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // --- simple vs advanced mode in the affinity editor ---

    /** What the editor opens simple mode for: one vCPU, one core of its own. */
    @Test
    public void oneToOneRecognisesTheSimpleModeShape() {
        var affinity = CpuPlacementPlan.parseAffinity("0=0:1=1:2=2");
        assertTrue(CpuPlacementPlan.isOneToOne(affinity, 3));
        assertEquals(List.of(0, 1, 2), CpuPlacementPlan.oneToOneHosts(affinity, 3));
    }

    /** Host cores need not be contiguous, or start at 0, to stay 1:1. */
    @Test
    public void oneToOneAcceptsAnyDistinctCores() {
        var affinity = CpuPlacementPlan.parseAffinity("0=4:1=7");
        assertTrue(CpuPlacementPlan.isOneToOne(affinity, 2));
        assertEquals(List.of(4, 7), CpuPlacementPlan.oneToOneHosts(affinity, 2));
    }

    /** Everything simple mode cannot say, and so has to open advanced for. */
    @Test
    public void oneToOneRejectsWhatSimpleModeCannotSay() {
        // A vCPU floating over two cores.
        assertFalse(CpuPlacementPlan.isOneToOne(
            CpuPlacementPlan.parseAffinity("0=4,5:1=6"), 2));
        // Two vCPUs sharing a core.
        assertFalse(CpuPlacementPlan.isOneToOne(
            CpuPlacementPlan.parseAffinity("0=4:1=4"), 2));
        // A vCPU with no binding at all.
        assertFalse(CpuPlacementPlan.isOneToOne(
            CpuPlacementPlan.parseAffinity("0=0:2=2"), 3));
        // Bound vCPUs the count no longer covers.
        assertFalse(CpuPlacementPlan.isOneToOne(
            CpuPlacementPlan.parseAffinity("0=0:1=1:2=2"), 2));
        // Nothing pinned is not 1:1 either; the dialog treats it separately.
        assertFalse(CpuPlacementPlan.isOneToOne(new TreeMap<>(), 2));
        assertTrue(CpuPlacementPlan.oneToOneHosts(
            CpuPlacementPlan.parseAffinity("0=4,5:1=6"), 2).isEmpty());
    }

    /** Checking cores in simple mode: core order decides the vCPU numbering. */
    @Test
    public void oneToOneBuildsTheMapFromCheckedCores() {
        var affinity = CpuPlacementPlan.oneToOne(List.of(7, 4, 5));
        assertEquals("0=4:1=5:2=7", CpuPlacementPlan.formatAffinity(affinity));
        assertTrue(CpuPlacementPlan.isOneToOne(affinity, 3));
    }

    /**
     * Advanced to simple: each vCPU keeps the lowest core no earlier vCPU took,
     * and the rest of its cores are dropped.
     */
    @Test
    public void flattenKeepsOneCorePerVcpu() {
        assertEquals(List.of(4, 6),
            CpuPlacementPlan.flattenToOneToOne(
                CpuPlacementPlan.parseAffinity("0=4,5:1=6")));
        // A vCPU whose every core is already taken keeps nothing.
        assertEquals(List.of(4),
            CpuPlacementPlan.flattenToOneToOne(
                CpuPlacementPlan.parseAffinity("0=4:1=4")));
        // An already-1:1 map survives the trip unchanged.
        var oneToOne = CpuPlacementPlan.parseAffinity("0=0:1=1:2=2");
        assertEquals(List.of(0, 1, 2), CpuPlacementPlan.flattenToOneToOne(oneToOne));
    }

    /**
     * The GPU worker cpuset needs a GPU worker. The switch used to be the whole gate, so a VM with
     * no virtio-gpu device still had the directory made for it and still got
     * {@code --gpu-cgroup-path} on the command line -- a group whose only members would have been
     * the threads that device does not have.
     */
    @Test
    public void theGpuCpusetNeedsTheDeviceAndNotJustTheSwitch() {
        var item = DataItem.newObject();
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP, true);
        // The switch alone, with no screens object at all: the shape a config has before anything
        // said whether this VM has the device.
        assertFalse(CpuPlacementPlan.wantsGpuCgroup(item));

        var gpu0 = VMScreenConfig.of(item, VMScreenConfig.ID_GPU0);
        gpu0.setEnabled(true);
        assertTrue(CpuPlacementPlan.wantsGpuCgroup(item));

        // The switch is still the user's answer, and it still comes first.
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP, false);
        assertFalse(CpuPlacementPlan.wantsGpuCgroup(item));

        // The device going away takes the cpuset with it, without the stored switch being
        // rewritten -- the editor greys the rows, but a config saved before that does not change.
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP, true);
        gpu0.setEnabled(false);
        assertFalse(CpuPlacementPlan.wantsGpuCgroup(item));
        assertTrue(item.optBoolean(CpuPlacementPlan.KEY_GPU_CGROUP, false));

        // A simplefb screen is a display device, not the one with worker threads to pin.
        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        assertFalse(CpuPlacementPlan.wantsGpuCgroup(item));
    }

    /** Guards the map type the UI relies on for stable row ordering. */
    @Test
    public void orderedCopyIsSortedByVcpu() {
        Map<Integer, List<Integer>> in = new TreeMap<>();
        in.put(3, List.of(3));
        in.put(0, List.of(0));
        var out = CpuPlacementPlan.orderedCopy(in);
        assertEquals(List.of(0, 3), List.copyOf(out.keySet()));
    }
}
