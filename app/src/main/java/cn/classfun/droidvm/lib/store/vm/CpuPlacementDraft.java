// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Map;

/**
 * A vCPU placement as the editor holds it, before it becomes a
 * {@link CpuPlacementPlan}: the affinity map, the CPU count it is keyed
 * against, and the guest topology fields that describe it.
 *
 * <p>These five travel together everywhere -- they are one decision seen from
 * several sides, which {@link CpuPlacementPlan} explains -- so they cross the
 * editor/dialog boundary as one value instead of five positional arguments.
 * The affinity map is copied in, so a draft cannot be edited through the map
 * the caller still holds.
 */
public final class CpuPlacementDraft {
    /** vCPU index to the host cores it may run on; ordered, never null. */
    @NonNull
    public final Map<Integer, List<Integer>> affinity;
    /** The VM's CPU count. Simple mode derives it from the bound host cores. */
    public final int vcpuCount;
    /** Derive guest capacity/cluster from the affinity instead of the fields below. */
    public final boolean auto;
    /** Hand-written {@code --cpu-capacity}, only meaningful when {@link #auto} is off. */
    @NonNull
    public final String manualCapacity;
    /** Hand-written {@code --cpu-cluster}, only meaningful when {@link #auto} is off. */
    @NonNull
    public final String manualClusters;

    public CpuPlacementDraft(
        @NonNull Map<Integer, List<Integer>> affinity,
        int vcpuCount,
        boolean auto,
        @NonNull String manualCapacity,
        @NonNull String manualClusters
    ) {
        this.affinity = CpuPlacementPlan.orderedCopy(affinity);
        this.vcpuCount = Math.max(1, vcpuCount);
        this.auto = auto;
        this.manualCapacity = manualCapacity;
        this.manualClusters = manualClusters;
    }
}
