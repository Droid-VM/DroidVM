// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.hugepage;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.GuestPoolSizing;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;

/**
 * Is the huge-page reserve able to back this VM <em>right now</em>?
 *
 * <p>Every region a Gunyah VM gets at boot -- guest RAM, the GPU pools, swiotlb -- is served
 * from {@code gh_hugepage_reserve} as isolated 2 MB folios, which the hypervisor can take
 * without moving anything. When the pool is short the shortfall comes from ordinary movable
 * memory instead, and that memory cannot be handed over without migrating it out of CMA first.
 * On a phone with nothing spare that migration is where things end badly: measured outcomes were
 * a multi-minute whole-host stall that ended with the kernel OOM-killing crosvm, and a
 * {@code qcom_scm: Assign memory protection call failed -22} that reset the device.
 *
 * <p>The pool refills within a couple of seconds of a VM exiting, so the common way to hit this
 * is simply starting the next VM too soon. That makes the fix cheap: look before starting, and
 * wait after sizing the target. Foreground starts on older, non-resizable modules also
 * offer a warning before committing the start.
 *
 * <p>Asked only of the VMs it is about. A VM on any other hypervisor is not served from the reserve
 * and cannot be delayed by it, so {@link #appliesTo} answers no before anything is read and every
 * check on such a VM is free and silent.
 *
 * <p>Everything here is context-free and does shell I/O -- call it off the UI thread.
 */
public final class PoolPreflight {
    private static final String TAG = "PoolPreflight";
    private static final String SYSFS_PARAMS = "/sys/module/gh_hugepage_reserve/parameters";

    /** The reserve deals in 2 MB pages; every count here is in those. */
    public static final long PAGE_MB = 2;

    /**
     * CMA reclamation can take minutes. Allow five minutes of one-second polls after sizing
     * the reservation, with one acquire request early in the wait. A timeout fails the start.
     */
    private static final int WAIT_ATTEMPTS = 300;
    private static final long WAIT_INTERVAL_MS = 1000;
    private static final int ACQUIRE_AT = 5;

    private PoolPreflight() {
    }

    /** What the pool can serve versus what this VM will ask of it. */
    public static final class Status {
        /**
         * This VM draws on the reserve and the module is loaded, so the numbers below mean
         * something. False is the ordinary answer: see {@link #appliesTo}.
         */
        public final boolean applicable;
        /** {@code pool_avail}: 2 MB pages sitting in the reserve, free. */
        public final long availPages;
        /** Estimated 2 MB pages this VM's boot-time regions will take. */
        public final long neededPages;

        Status(boolean applicable, long availPages, long neededPages) {
            this.applicable = applicable;
            this.availPages = availPages;
            this.neededPages = neededPages;
        }

        public boolean isEnough() {
            return !applicable || availPages >= neededPages;
        }

        public long availMb() {
            return availPages * PAGE_MB;
        }

        public long neededMb() {
            return neededPages * PAGE_MB;
        }

        public long shortMb() {
            return Math.max(0, neededPages - availPages) * PAGE_MB;
        }

        @NonNull
        @Override
        public String toString() {
            return fmt("pool_avail=%d need=%d (%d MB / %d MB)",
                availPages, neededPages, availMb(), neededMb());
        }
    }

    /**
     * Whether the reserve has anything to do with this VM.
     *
     * <p>Only a Gunyah VM is served from it. That is what the reserve is: isolated folios for the
     * one hypervisor that takes guest memory away from the host, and the danger it exists to avoid
     * -- migrating pages out of CMA to hand them over -- is that hypervisor's transfer and nobody
     * else's. KVM and GenieZone hand over nothing, and a TCG guest is ordinary process memory.
     * Their VMs pay the reserve no attention, so the reserve must pay them none: a prompt or a wait
     * for a pool they will not draw on is a delay with no failure behind it.</p>
     *
     * <p>The module being loaded is the second half of the question, not the first. It ships for
     * Qualcomm SoCs alone -- {@code match.json} gates it on {@code soc_vendor}, and the kernel-module
     * page hides the card everywhere else -- so on most phones the answer is no twice over. Read
     * here rather than assumed, because a QEMU-on-Gunyah VM on a Qualcomm phone is both.</p>
     */
    public static boolean appliesTo(@NonNull DataItem item) {
        var backend = optEnum(item, "backend", VMBackend.DEFAULT);
        var configured = optEnum(item, "hypervisor", VMHypervisor.DEFAULT);
        return VMHypervisor.resolveConfigured(backend, configured) == VMHypervisor.GUNYAH;
    }

    /** The daemon can size this pool at start; older modules retain the GUI preflight. */
    public static boolean canResize(@NonNull DataItem item) {
        if (!appliesTo(item)) return false;
        try {
            return shellCheckExists(pathJoin(SYSFS_PARAMS, "pool_want"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Live non-CMA target only. Never persist it or write the CMA reservoir knobs. */
    public static boolean setReservedPages(long pages) {
        try {
            if (writeKnob("pool_want", Long.toString(pages))) {
                long actual = readPages("pool_want", -1);
                // Kernels may round a nonzero target up to pageblock alignment.
                if (actual == pages || (pages > 0 && actual > pages)) return true;
            }
            Log.w(TAG, fmt("Could not set huge-page reserve to %d pages", pages));
        } catch (Exception e) {
            Log.w(TAG, "Could not resize huge-page reserve", e);
        }
        return false;
    }

    /** Reads the reserve and sizes this VM against it. Never throws. */
    @NonNull
    public static Status check(@NonNull DataItem item) {
        if (!appliesTo(item))
            return new Status(false, 0, 0);
        long avail = readPages("pool_avail", -1);
        if (avail < 0)
            return new Status(false, 0, 0);
        return new Status(true, avail, neededPages(item));
    }

    /**
     * The 2 MB pages this VM's <em>boot-time</em> regions will take out of the reserve.
     *
     * <p>The memory size plus the guest pool, and nothing else. Everything else the backend passes
     * is already inside {@code --mem}: crosvm carves the swiotlb and the framebuffer out of it, and
     * as of the per-pool {@code consume_system_mem} tag so are the three renderer host pools --
     * whichever of them a route uses, the VM still costs what its memory field says. Only the guest
     * pool is added on top, because it is video memory the user asked for beside the RAM rather
     * than out of it - and only when the backend will actually pass one, which
     * {@link GuestPoolSizing} decides for both sides.
     *
     * <p>Growth grants (the runtime SHARE path) are deliberately not counted -- they happen later,
     * one blob at a time, and a VM that cannot grow still boots. That is also why the guest pool
     * contributes its pre-allocation and not its window.
     */
    public static long neededPages(@NonNull DataItem item) {
        long mb = Math.max(item.optLong("memory_mb", 512), 64);
        // Exactly what the backend will pre-allocate: nothing for a host-visible-RAM VM, and
        // for gfxstream only with udmabuf. One rule, shared with the command builder.
        mb += GuestPoolSizing.bootGuestPreallocMb(item);
        return (mb + PAGE_MB - 1) / PAGE_MB;
    }

    /**
     * Waits for actual free pages before starting, including on reboot. False forbids startup.
     * A registered reservation requires the module even if it disappears before the first poll.
     * Cancellation and daemon shutdown are checked each second.
     */
    public static boolean waitForPool(@NonNull DataItem item, boolean required,
                                      @NonNull BooleanSupplier abort) {
        if (!appliesTo(item)) return true;
        try {
            if (!required && !shellCheckExists(SYSFS_PARAMS)) return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not check huge-page reserve", e);
            return false;
        }
        long needed = neededPages(item);
        // Once the module is present, a failed read or module removal is not readiness.
        return waitForPool(() -> new Status(true, readPages("pool_avail", -1), needed),
            WAIT_ATTEMPTS, WAIT_INTERVAL_MS, ACQUIRE_AT, abort, PoolPreflight::acquire);
    }

    /** Polling policy separated from sysfs I/O so delayed refill and failures can be tested. */
    static boolean waitForPool(@NonNull Supplier<Status> readStatus, int attempts, long sleepMs,
                               int acquireAt, @NonNull BooleanSupplier abort,
                               @NonNull Runnable acquirePages) {
        if (abort.getAsBoolean() || Thread.currentThread().isInterrupted()) return false;
        var status = readStatus.get();
        if (status.isEnough())
            return true;
        Log.i(TAG, fmt("waiting for the huge-page reserve: %s", status));
        for (int i = 1; i <= attempts; i++) {
            if (abort.getAsBoolean()) {
                Log.i(TAG, fmt("the wait for the reserve was called off after %d attempt(s)", i - 1));
                return false;
            }
            if (i == acquireAt) {
                Log.i(TAG, fmt("reserve still short at attempt %d; asking it to acquire", i));
                acquirePages.run();
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (abort.getAsBoolean()) return false;
            status = readStatus.get();
            if (status.isEnough()) {
                Log.i(TAG, fmt("reserve recovered after %d attempt(s): %s", i, status));
                return true;
            }
        }
        Log.w(TAG, fmt("reserve not ready after %d attempt(s): %s -- refusing to start",
            attempts, status));
        return false;
    }

    /** Request refill once, with a fallback for older modules. */
    private static void acquire() {
        if (!writeKnob("acquire", "2")) writeKnob("manual_refill", "1");
    }

    private static boolean writeKnob(@NonNull String knob, @NonNull String value) {
        var path = pathJoin(SYSFS_PARAMS, knob);
        if (!shellCheckExists(path))
            return false;
        return run("echo %s > %s", value, path).isSuccess();
    }

    private static long readPages(@NonNull String knob, long fallback) {
        var path = pathJoin(SYSFS_PARAMS, knob);
        try {
            if (!shellCheckExists(path))
                return fallback;
            return Long.parseLong(shellReadFile(path).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
