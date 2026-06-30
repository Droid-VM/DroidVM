package cn.classfun.droidvm.ui.hugepage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A single process that the gh_hugepage_reserve module has served pool pages to.
 * Sourced from the module's {@code vm_owners} sysfs file and enriched with live
 * /proc state. {@link #vmName} is a best-effort match against running VMs and may
 * be null for non-DroidVM processes that still consume the reserved pool.
 */
public final class HugePageProcess {
    public final int pid;
    @NonNull
    public final String comm;
    /**
     * Pool pages served to this owner (cumulative, 2MiB each), or -1 when this
     * row came from a system-wide /proc scan rather than module attribution.
     */
    public final long servedPages;
    /**
     * Current THP-backed memory mapped by the process, in kB (-1 if unknown).
     * Sum of AnonHugePages + ShmemPmdMapped: crosvm guest RAM is shmem-backed
     * THP (ShmemPmdMapped), so AnonHugePages alone is 0 for VMs.
     */
    public final long thpKb;
    /** Process state char from /proc/<pid>/status, e.g. 'R','S','D' ('?' unknown). */
    public final char state;
    /** Matched VM name, or null when no running VM owns this pid. */
    @Nullable
    public final String vmName;
    /** Whether /proc/<pid> still exists (process alive). */
    public final boolean alive;
    /** Assigned display color (icon tint + bar segment). */
    public final int color;
    /** True for the synthetic "unattributed" pool usage row (no actions). */
    public final boolean unknown;
    /** True for the synthetic "waiting for acquire" deficit row (acquire btn). */
    public final boolean acquire;

    public HugePageProcess(
        int pid, @NonNull String comm, long servedPages,
        long thpKb, char state, @Nullable String vmName, boolean alive,
        int color, boolean unknown, boolean acquire
    ) {
        this.pid = pid;
        this.comm = comm;
        this.servedPages = servedPages;
        this.thpKb = thpKb;
        this.state = state;
        this.vmName = vmName;
        this.alive = alive;
        this.color = color;
        this.unknown = unknown;
        this.acquire = acquire;
    }

    /** THP occupancy expressed in 2MiB pages (-1 if unknown). */
    public long thpPages() {
        return thpKb < 0 ? -1 : thpKb / 2048;
    }

    public boolean isUninterruptibleSleep() {
        return state == 'D';
    }
}
