// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.utils.CpuUtils;

/**
 * Where a VM's vCPUs run on the host, and what the guest is told about it.
 *
 * <p>The three crosvm flags this resolves are one decision seen from three
 * sides, not independent knobs:
 * <ul>
 *   <li>{@code --cpu-affinity} pins each vCPU thread to host cores.
 *   <li>{@code --cpu-capacity} writes {@code capacity-dmips-mhz} into the guest
 *       device tree, so the guest scheduler knows how strong each vCPU is.
 *   <li>{@code --cpu-cluster} writes the guest {@code cpu-map}, so the guest
 *       sees the same big/little split as the host.
 * </ul>
 * The last two are keyed by <em>guest</em> vCPU index and their correct values
 * follow from the first plus host topology, which is why {@link #KEY_AUTO}
 * (default on) derives them and the manual fields only exist as an override.
 *
 * <p>Syntax matters here: crosvm's per-vCPU affinity form separates assignments
 * with {@code ':'} and uses {@code ','} only inside one assignment's host set,
 * i.e. {@code 0=4,5:1=6} means vCPU0 floats over host cores 4 and 5 while vCPU1
 * is pinned to core 6. A vCPU absent from the map gets no mask at all.
 */
public final class CpuPlacementPlan {
    public static final String KEY_AFFINITY = "cpu_affinity";
    public static final String KEY_AUTO = "cpu_topology_auto";
    public static final String KEY_CAPACITY = "cpu_capacity";
    public static final String KEY_CLUSTERS = "cpu_clusters";
    public static final String KEY_GPU_CGROUP = "gpu_cgroup_enabled";
    public static final String KEY_GPU_CGROUP_PATH = "gpu_cgroup_path";
    public static final String KEY_GPU_CGROUP_CPUS = "gpu_cgroup_cpus";

    public static final String DEFAULT_GPU_CGROUP_PATH = "/dev/cpuset/gpuworker";
    /** Separates clusters in the stored {@link #KEY_CLUSTERS} string. */
    private static final String CLUSTER_SEP = ";";

    /** vCPU index to the host cores it may run on; ascending, never null. */
    @NonNull
    public final Map<Integer, List<Integer>> affinity;
    /** vCPU index to guest-visible capacity; only for vCPUs that have one. */
    @NonNull
    public final Map<Integer, Long> capacity;
    /** Guest cluster membership, each entry a set of vCPU indices. */
    @NonNull
    public final List<List<Integer>> clusters;

    private CpuPlacementPlan(
        @NonNull Map<Integer, List<Integer>> affinity,
        @NonNull Map<Integer, Long> capacity,
        @NonNull List<List<Integer>> clusters
    ) {
        this.affinity = affinity;
        this.capacity = capacity;
        this.clusters = clusters;
    }

    /**
     * Resolve a stored config into the placement actually to be applied. An
     * empty affinity string yields an empty plan: capacity and clusters are
     * dropped along with it, since without knowing which host core backs a vCPU
     * there is nothing truthful to tell the guest.
     */
    @NonNull
    public static CpuPlacementPlan of(@NonNull DataItem item) {
        var affinity = parseAffinity(item.optString(KEY_AFFINITY, ""));
        if (affinity.isEmpty())
            return new CpuPlacementPlan(affinity, new TreeMap<>(), new ArrayList<>());
        if (item.optBoolean(KEY_AUTO, true)) {
            var cap = deriveCapacity(affinity, CpuUtils.getCores());
            int vcpuCount = (int) Math.max(item.optLong("cpu_count", 1), 1);
            return new CpuPlacementPlan(affinity, cap, deriveClusters(cap, vcpuCount));
        }
        return new CpuPlacementPlan(
            affinity,
            parseCapacity(item.optString(KEY_CAPACITY, "")),
            parseClusters(item.optString(KEY_CLUSTERS, ""))
        );
    }

    /** Appends the crosvm flags for this plan; a no-op when no vCPU is pinned. */
    public void appendArgs(@NonNull List<String> args) {
        if (affinity.isEmpty()) return;
        args.add("--cpu-affinity");
        args.add(formatAffinity(affinity));
        if (!capacity.isEmpty()) {
            args.add("--cpu-capacity");
            args.add(formatCapacity(capacity));
        }
        // One flag per cluster; a lone cluster is what crosvm does by default
        // anyway, so it is not worth an FDT cpu-map.
        if (clusters.size() > 1) {
            for (var cluster : clusters) {
                if (cluster.isEmpty()) continue;
                args.add("--cpu-cluster");
                args.add(CpuUtils.compactRanges(joinCsv(cluster)));
            }
        }
    }

    // --- affinity ---

    /**
     * Parse the per-vCPU affinity form. Assignments are {@code ':'}-separated
     * and each maps one vCPU to a CPUSET; malformed assignments and empty host
     * sets are dropped. The plain global-CPUSET form crosvm also accepts is not
     * represented here -- the editor always writes per-vCPU assignments -- so a
     * hand-written global mask parses to empty and is simply not carried over.
     */
    @NonNull
    public static Map<Integer, List<Integer>> parseAffinity(@NonNull String spec) {
        var out = new TreeMap<Integer, List<Integer>>();
        if (spec.trim().isEmpty()) return out;
        for (var assignment : spec.split(":")) {
            assignment = assignment.trim();
            if (assignment.isEmpty()) continue;
            int eq = assignment.indexOf('=');
            if (eq <= 0) continue;
            int vcpu;
            try {
                vcpu = Integer.parseInt(assignment.substring(0, eq).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (vcpu < 0) continue;
            var hosts = CpuUtils.parseCpuSet(assignment.substring(eq + 1));
            if (hosts.isEmpty()) continue;
            out.put(vcpu, hosts);
        }
        return out;
    }

    @NonNull
    public static String formatAffinity(@NonNull Map<Integer, List<Integer>> affinity) {
        var sb = new StringBuilder();
        for (var entry : new TreeMap<>(affinity).entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append(':');
            sb.append(entry.getKey()).append('=')
                .append(CpuUtils.compactRanges(joinCsv(entry.getValue())));
        }
        return sb.toString();
    }

    /**
     * True when {@code affinity} is exactly what the editor's simple mode can
     * express: every vCPU below {@code vcpuCount} bound to one host core of its
     * own, no core shared, and nothing bound past the count. A vCPU floating
     * over several cores, an unbound vCPU or two vCPUs on one core all need the
     * per-vCPU editor to be described, and answer false here.
     */
    public static boolean isOneToOne(
        @NonNull Map<Integer, List<Integer>> affinity, int vcpuCount
    ) {
        if (vcpuCount <= 0 || affinity.size() != vcpuCount) return false;
        var hosts = new TreeSet<Integer>();
        for (int vcpu = 0; vcpu < vcpuCount; vcpu++) {
            var bound = affinity.get(vcpu);
            if (bound == null || bound.size() != 1) return false;
            if (!hosts.add(bound.get(0))) return false;
        }
        return true;
    }

    /**
     * The 1:1 map over {@code hostCores}: lowest core index becomes vCPU 0, the
     * next vCPU 1, and so on. Inverse of {@link #oneToOneHosts}.
     */
    @NonNull
    public static Map<Integer, List<Integer>> oneToOne(@NonNull Collection<Integer> hostCores) {
        var out = new TreeMap<Integer, List<Integer>>();
        int vcpu = 0;
        for (var host : new TreeSet<>(hostCores))
            out.put(vcpu++, new ArrayList<>(List.of(host)));
        return out;
    }

    /**
     * The host cores a 1:1 map pins, ascending; empty when the map is not 1:1
     * over {@code vcpuCount} vCPUs.
     */
    @NonNull
    public static List<Integer> oneToOneHosts(
        @NonNull Map<Integer, List<Integer>> affinity, int vcpuCount
    ) {
        if (!isOneToOne(affinity, vcpuCount)) return new ArrayList<>();
        var hosts = new TreeSet<Integer>();
        for (var bound : affinity.values()) hosts.add(bound.get(0));
        return new ArrayList<>(hosts);
    }

    /**
     * The 1:1 selection closest to an arbitrary map, for the advanced-to-simple
     * switch: each vCPU in turn keeps the lowest core it is bound to that an
     * earlier vCPU has not already claimed; its remaining cores, and a vCPU left
     * with nothing to claim, are dropped. Ascending, so the result can be handed
     * straight to {@link #oneToOne} -- which is why the vCPU a core ends up on
     * need not be the one it came from.
     */
    @NonNull
    public static List<Integer> flattenToOneToOne(
        @NonNull Map<Integer, List<Integer>> affinity
    ) {
        var taken = new TreeSet<Integer>();
        for (var entry : new TreeMap<>(affinity).entrySet()) {
            for (var host : entry.getValue())
                if (taken.add(host)) break;
        }
        return new ArrayList<>(taken);
    }

    // --- capacity ---

    /** Parse {@code 0=792,6=1024}; unparsable pairs are dropped. */
    @NonNull
    public static Map<Integer, Long> parseCapacity(@NonNull String spec) {
        var out = new TreeMap<Integer, Long>();
        for (var pair : spec.split(",")) {
            pair = pair.trim();
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                var vcpu = Integer.parseInt(pair.substring(0, eq).trim());
                var cap = Long.parseLong(pair.substring(eq + 1).trim());
                if (vcpu >= 0 && cap > 0) out.put(vcpu, cap);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    @NonNull
    public static String formatCapacity(@NonNull Map<Integer, Long> capacity) {
        var sb = new StringBuilder();
        for (var entry : new TreeMap<>(capacity).entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(fmt("%d=%d", entry.getKey(), entry.getValue()));
        }
        return sb.toString();
    }

    /**
     * Capacity of each pinned vCPU: the weakest host core it can land on. Taking
     * the minimum rather than the maximum keeps the guest scheduler honest -- a
     * vCPU floating across a little and a big core can end up on the little one,
     * and promising the big core's capacity would make the guest over-commit it.
     */
    @NonNull
    public static Map<Integer, Long> deriveCapacity(
        @NonNull Map<Integer, List<Integer>> affinity,
        @NonNull List<CpuUtils.CpuCore> cores
    ) {
        var byIndex = new TreeMap<Integer, Long>();
        for (var core : cores) byIndex.put(core.index, core.capacity);
        var out = new TreeMap<Integer, Long>();
        for (var entry : affinity.entrySet()) {
            long min = 0;
            for (var host : entry.getValue()) {
                var cap = byIndex.get(host);
                if (cap == null || cap <= 0) continue;
                min = min == 0 ? cap : Math.min(min, cap);
            }
            if (min > 0) out.put(entry.getKey(), min);
        }
        return out;
    }

    // --- clusters ---

    /** Parse {@code 0-5;6} into one vCPU list per cluster. */
    @NonNull
    public static List<List<Integer>> parseClusters(@NonNull String spec) {
        var out = new ArrayList<List<Integer>>();
        for (var group : spec.split(CLUSTER_SEP)) {
            var members = CpuUtils.parseCpuSet(group);
            if (!members.isEmpty()) out.add(members);
        }
        return out;
    }

    @NonNull
    public static String formatClusters(@NonNull List<List<Integer>> clusters) {
        var sb = new StringBuilder();
        for (var cluster : clusters) {
            if (cluster.isEmpty()) continue;
            if (sb.length() > 0) sb.append(CLUSTER_SEP);
            sb.append(CpuUtils.compactRanges(joinCsv(cluster)));
        }
        return sb.toString();
    }

    /**
     * The vCPUs sharing each capacity value, weakest capacity first. Both the
     * cluster split and the UI's capacity summary are views of this grouping.
     */
    @NonNull
    public static NavigableMap<Long, List<Integer>> groupByCapacity(
        @NonNull Map<Integer, Long> capacity) {
        var byCapacity = new TreeMap<Long, List<Integer>>();
        for (var entry : new TreeMap<>(capacity).entrySet()) {
            byCapacity.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                .add(entry.getKey());
        }
        return byCapacity;
    }

    /**
     * Group vCPUs of equal capacity into one cluster each, weakest first, which
     * reproduces the host's big/little split on the guest side.
     *
     * <p>Every vCPU below {@code vcpuCount} lands in exactly one cluster: crosvm
     * builds the guest {@code cpu-map} all-or-nothing, so a vCPU left out of the
     * cluster list would get no topology placement at all while its siblings do.
     * An unpinned vCPU floats across every host core, so the weakest cluster is
     * both the truthful and the conservative home for it.
     */
    @NonNull
    public static List<List<Integer>> deriveClusters(
        @NonNull Map<Integer, Long> capacity, int vcpuCount) {
        var byCapacity = groupByCapacity(capacity);
        if (byCapacity.isEmpty()) return new ArrayList<>();
        var weakest = byCapacity.firstEntry().getValue();
        for (int vcpu = 0; vcpu < vcpuCount; vcpu++) {
            if (!capacity.containsKey(vcpu)) weakest.add(vcpu);
        }
        weakest.sort(Integer::compareTo);
        return new ArrayList<>(byCapacity.values());
    }

    // --- shared helpers ---

    /** vCPU indices that appear in more than one cluster (crosvm rejects those). */
    @NonNull
    public static List<Integer> findClusterOverlaps(@NonNull List<List<Integer>> clusters) {
        var seen = new TreeSet<Integer>();
        var dupes = new TreeSet<Integer>();
        for (var cluster : clusters)
            for (var vcpu : cluster)
                if (!seen.add(vcpu)) dupes.add(vcpu);
        return new ArrayList<>(dupes);
    }

    /** Host cores shared between a vCPU affinity map and a CPUSET spec. */
    @NonNull
    public static List<Integer> findHostOverlaps(
        @NonNull Map<Integer, List<Integer>> affinity,
        @NonNull String cpuSet
    ) {
        var other = new TreeSet<>(CpuUtils.parseCpuSet(cpuSet));
        var shared = new TreeSet<Integer>();
        for (var hosts : affinity.values())
            for (var host : hosts)
                if (other.contains(host)) shared.add(host);
        return new ArrayList<>(shared);
    }

    /** Ordered copy keyed by vCPU, so callers can edit without losing order. */
    @NonNull
    public static Map<Integer, List<Integer>> orderedCopy(
        @NonNull Map<Integer, List<Integer>> affinity
    ) {
        var out = new LinkedHashMap<Integer, List<Integer>>();
        for (var entry : new TreeMap<>(affinity).entrySet())
            out.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        return out;
    }

    @NonNull
    private static String joinCsv(@NonNull List<Integer> values) {
        var sb = new StringBuilder();
        for (var value : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(value);
        }
        return sb.toString();
    }
}
