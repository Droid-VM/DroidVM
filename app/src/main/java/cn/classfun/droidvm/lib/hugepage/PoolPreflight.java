// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.hugepage;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.util.Log;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.GuestPoolSizing;

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
 * either wait (background starts) or say so (foreground starts).
 *
 * <p>Everything here is context-free and does shell I/O -- call it off the UI thread.
 */
public final class PoolPreflight {
    private static final String TAG = "PoolPreflight";
    private static final String SYSFS_PARAMS = "/sys/module/gh_hugepage_reserve/parameters";

    /** The reserve deals in 2 MB pages; every count here is in those. */
    public static final long PAGE_MB = 2;

    /**
     * The waiting policy for a start nobody is watching -- auto-start at daemon boot, and the
     * relaunch that follows a guest reboot. Ten looks a second apart, asking the module to fetch
     * more half way through, and start anyway at the end. See {@link #waitForPool}.
     */
    public static final int BACKGROUND_ATTEMPTS = 10;
    public static final long BACKGROUND_INTERVAL_MS = 1000;
    public static final int BACKGROUND_ACQUIRE_AT = 5;

    /**
     * The same policy, with more room, for the relaunch after a guest reboot. That start races
     * the reserve taking back the memory the same VM has only just released, and measured on
     * device that takes about ten seconds (drm2kgsl: enough again at ~9 s, full at ~16 s; venus:
     * enough at ~9 s, full at ~13 s) -- too close to the ten of a plain background start to leave
     * it there. Twice the measured worst case, and still bounded.
     */
    public static final int RELAUNCH_ATTEMPTS = 20;

    private PoolPreflight() {
    }

    /** What the pool can serve versus what this VM will ask of it. */
    public static final class Status {
        /** The module is loaded, so the numbers below mean something. */
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

    /** Reads the reserve and sizes this VM against it. Never throws. */
    @NonNull
    public static Status check(@NonNull DataItem item) {
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
     * Waits for the reserve to cover this VM, for background starts (auto-start, and the daemon
     * re-launching VMs after a reboot) where there is nobody to ask.
     *
     * <p>One second between looks, because a normal refill lands in about two. Half way through
     * it asks the module to go and get more; that is worth one shot and no more, since a reserve
     * that cannot be filled will not be filled by asking twice. If the wait runs out we start
     * anyway: refusing to boot a VM the user asked to auto-start is worse than a boot that may
     * be slow, and the VMM has its own guard at the point where it actually hands memory over.
     *
     * @return true if the pool covered the VM before the attempts ran out
     */
    /** {@link #waitForPool} with the shared background policy. */
    public static boolean waitForPool(@NonNull DataItem item) {
        return waitForPool(item, BACKGROUND_ATTEMPTS, BACKGROUND_INTERVAL_MS, BACKGROUND_ACQUIRE_AT);
    }

    public static boolean waitForPool(@NonNull DataItem item, int attempts, long sleepMs, int acquireAt) {
        var status = check(item);
        if (!status.applicable || status.isEnough())
            return true;
        Log.i(TAG, fmt("waiting for the huge-page reserve: %s", status));
        for (int i = 1; i <= attempts; i++) {
            if (i == acquireAt) {
                Log.i(TAG, fmt("reserve still short at attempt %d; asking it to acquire", i));
                acquire(2);
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            status = check(item);
            if (status.isEnough()) {
                Log.i(TAG, fmt("reserve recovered after %d attempt(s): %s", i, status));
                return true;
            }
        }
        Log.w(TAG, fmt("reserve still short after %d attempt(s): %s -- starting anyway",
            attempts, status));
        return false;
    }

    /**
     * Asks the module to grow the reserve ({@code acquire=<mode>}), falling back to the older
     * {@code manual_refill} knob. Best-effort: the caller carries on either way.
     */
    public static boolean acquire(int mode) {
        if (writeKnob("acquire", Integer.toString(mode)))
            return true;
        return writeKnob("manual_refill", "1");
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
