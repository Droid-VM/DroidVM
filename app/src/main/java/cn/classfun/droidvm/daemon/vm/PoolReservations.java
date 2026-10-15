// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongPredicate;

/** Daemon-wide non-CMA pool budget. A VM keeps its reservation across relaunches. */
final class PoolReservations {
    private final Map<UUID, Long> pagesByVm = new HashMap<>();
    private final LongPredicate writeTarget;

    PoolReservations(LongPredicate writeTarget) {
        this.writeTarget = writeTarget;
    }

    // Keep accounting and writes under the same lock: a concurrent exit must not overwrite
    // a newer start's target. Duplicate starts (including reboot) do not write anything.
    synchronized boolean reserve(UUID vmId, long pages) {
        if (pages <= 0 || pagesByVm.containsKey(vmId)) return true;
        pagesByVm.put(vmId, pages);
        if (writeTarget.test(totalPages())) return true;
        // A failed/read-back-rejected write may still have changed the kernel target.
        pagesByVm.remove(vmId);
        writeTarget.test(totalPages());
        return false;
    }

    synchronized void release(UUID vmId) {
        if (pagesByVm.remove(vmId) == null) return;
        writeTarget.test(totalPages());
    }

    synchronized boolean contains(UUID vmId) {
        return pagesByVm.containsKey(vmId);
    }

    private long totalPages() {
        long total = 0;
        for (long pages : pagesByVm.values()) total += pages;
        return total;
    }
}
