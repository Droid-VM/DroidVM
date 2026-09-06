// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * How much guest-owned VRAM pool a VM actually gets at boot, as one rule shared by the crosvm
 * command builder (which passes it) and the huge-page preflight (which budgets for it). The two
 * drifted once: the daemon zeroed the pool for host-visible-RAM modes while the preflight still
 * added it, so a 5 GB pseudo-unprotected VM was told it needed 6 GB of reserve.
 *
 * <p>The guest-alloc pool buys the host access to buffers the guest allocated, which in an
 * ordinary protected VM it does not otherwise have. When the host can already reach the guest's
 * RAM (an unprotected VM, or a pseudo-unprotected one whose window is shared back before the
 * payload runs) the pool is memory taken from the guest to solve a problem that is not
 * happening, so it is dropped. gfxstream additionally needs udmabuf, which is what gates
 * guest-created handles; without it there is nothing to pre-allocate.
 */
public final class GuestPoolSizing {
    private GuestPoolSizing() {
    }

    /** The host can read the guest's RAM directly, so no guest pool is passed. */
    public static boolean hostVisibleRam(@NonNull DataItem item) {
        var pvm = optEnum(item, "protected_vm", ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        return pvm == ProtectedVM.PROTECTED_NORMAL || pvm == ProtectedVM.PSEUDO_UNPROTECTED;
    }

    /** The pool window ({@code gpu-guest-mb}) crosvm will be given, 0 when none. */
    public static long bootGuestPoolMb(@NonNull DataItem item) {
        if (!VMScreenConfig.hasGpuDevice(item)) return 0;
        if (hostVisibleRam(item)) return 0;
        long pool = Math.max(item.optLong("gpu_guest_pool_mb", 0), 0);
        var backend = optEnum(item, "gpu_backend", GpuBackend.NONE);
        if (backend == GpuBackend.GPU_GFXSTREAM)
            return item.optBoolean("gpu_udmabuf", true) ? pool : 0;
        if (backend == GpuBackend.GPU_VIRGLRENDERER)
            return pool;
        return 0;
    }

    /**
     * The part of that window pre-allocated at boot ({@code gpu-guest-prealloc-mb}) - what the
     * huge-page reserve pays up front. Older configs carry no prealloc field and keep the whole
     * pool preallocated; growth grants come later, one blob at a time, and are not counted.
     */
    public static long bootGuestPreallocMb(@NonNull DataItem item) {
        long pool = bootGuestPoolMb(item);
        if (pool <= 0) return 0;
        return Math.max(Math.min(item.optLong("gpu_guest_prealloc_mb", pool), pool), 0);
    }
}
