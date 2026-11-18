// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * Whether a VM is given hardware video acceleration.
 *
 * <p>One switch for both directions. In the guest they are two devices -- a virtio-media device is
 * one V4L2 node is one function, so a decoder and an encoder cannot share one -- but they are one
 * piece of hardware to whoever ticks the box, and there is no host on which one would work and the
 * other would not.</p>
 *
 * <p>What the switch buys is the whole virtio-media transport: with it on, the daemon's crosvm
 * command line carries the two pools ({@code media-host-mb} / {@code media-guest-mb}, Gunyah
 * only) and the huge-page preflight budgets for the guest one. With it off none of that is
 * passed. The choice is stored with the VM, so it survives a reboot without any
 * {@code setprop}; a VM whose config predates these keys reads as off with the default pools,
 * exactly like a VM that never ticked the box.</p>
 */
public final class VpuConfig {
    public static final String KEY_ENABLED = "vpu_enabled";
    public static final String KEY_HOST_POOL_MB = "vpu_host_pool_mb";
    public static final String KEY_GUEST_POOL_MB = "vpu_guest_pool_mb";

    /**
     * The {@code media_host} pool a VM gets when nobody chose a size.
     *
     * <p>320 MiB: {@code ffmpeg -f v4l2 -video_size 3840x2160 -i /dev/video0} asks the camera
     * device for 22 buffers of 12,441,600 B = 261 MiB, and 256 MiB refused the 22nd -- to a
     * client that has no way to ask for fewer. 320 MiB holds 26 such buffers: the 22 plus four.
     * The host pool is {@code consume_system_mem} on the crosvm side, so it comes out of the VM's
     * {@code --mem} and costs the huge-page reserve nothing
     * ({@link cn.classfun.droidvm.lib.hugepage.PoolPreflight#neededPages}). A multiple of the
     * 2 MiB huge-page granule, like every pool size here; the daemon's
     * {@code default_vpu_host_pool_mb} is the same number.</p>
     */
    public static final int DEFAULT_HOST_POOL_MB = 320;

    /**
     * The {@code media_guest} pool a protected VM gets when nobody chose a size.
     *
     * <p>192 MiB: a 4K hardware encode's driver-owned OUTPUT queue is 11 buffers of
     * 12,441,600 B = 130.5 MiB, which did not fit 128 MiB. 192 MiB holds 16: the 11 plus five,
     * so a second driver-owned queue in the same VM still fits. Unlike the host pool this one is
     * memory beside the VM's RAM, so it is added to the huge-page preflight.</p>
     */
    public static final int DEFAULT_GUEST_POOL_MB = 192;

    private VpuConfig() {
    }

    public static boolean isEnabled(@NonNull DataItem config) {
        return config.optBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(@NonNull DataItem config, boolean enabled) {
        config.set(KEY_ENABLED, enabled);
    }

    /**
     * Where the host puts the buffers it allocates for the guest to map.
     *
     * <p>Always present: host-allocated buffers are how virtio-media works on every hypervisor,
     * and the pool is only a different base for the offset the host already returns.</p>
     */
    public static int getHostPoolMb(@NonNull DataItem config) {
        return (int) config.optLong(KEY_HOST_POOL_MB, DEFAULT_HOST_POOL_MB);
    }

    public static void setHostPoolMb(@NonNull DataItem config, int mb) {
        config.set(KEY_HOST_POOL_MB, mb);
    }

    /** The stored guest pool size, whether or not this VM can use one. */
    public static int getGuestPoolMb(@NonNull DataItem config) {
        return (int) config.optLong(KEY_GUEST_POOL_MB, DEFAULT_GUEST_POOL_MB);
    }

    public static void setGuestPoolMb(@NonNull DataItem config, int mb) {
        config.set(KEY_GUEST_POOL_MB, mb);
    }

    /**
     * Whether a guest-side pool means anything for {@code pvm}.
     *
     * <p>It only does when the host cannot read guest memory. Everywhere else the guest driver's
     * ordinary allocation is already reachable, and declaring a pool would replace a working path
     * with a bounded one for no gain. The value stays in the config either way, so switching the
     * protection mode back does not lose it.</p>
     */
    public static boolean guestPoolApplies(@Nullable ProtectedVM pvm) {
        return pvm == ProtectedVM.PROTECTED_PROTECTED
            || pvm == ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;
    }

    /**
     * The {@code media-host-mb} this VM is passed, or 0 for "no host pool".
     *
     * <p>The switch alone: with it off there is no host pool, no guest pool and no media device,
     * whatever the peripheral list says. The size is the configured one, falling back to
     * {@link #DEFAULT_HOST_POOL_MB} when the switch is on and the config says zero or less: on
     * Gunyah crosvm refuses to create a virtio-media device with no {@code media_host} pool, so
     * {@code media-host-mb=0} would be a VM that does not boot. {@link #hostPoolIsDefaulted} says
     * when it happened; the daemon logs it. A fallback for old and hand-written configs only: the
     * editor refuses a host pool below 1 MB with the switch on.</p>
     */
    public static long hostPoolMbFor(@NonNull DataItem config) {
        if (!isEnabled(config)) return 0;
        long mb = getHostPoolMb(config);
        return mb > 0 ? mb : DEFAULT_HOST_POOL_MB;
    }

    /**
     * True when {@link #hostPoolMbFor} had to substitute the default because the VM asked for
     * video acceleration and stored a host pool size of zero or less.
     */
    public static boolean hostPoolIsDefaulted(@NonNull DataItem config) {
        return isEnabled(config) && getHostPoolMb(config) <= 0;
    }

    /**
     * The guest pool size to pass to crosvm, or 0 for "do not create one".
     *
     * <p>0 is not a smaller pool, it is no {@code media_guest} node at all: with nothing in
     * /reserved-memory to find, the guest driver falls back to its stock behaviour of allocating
     * from system RAM. A VM with the switch off gets neither pool: the sizes stay in the config
     * so that turning it back on restores them, but a stored size is not a request.</p>
     */
    public static int guestPoolMbFor(@NonNull DataItem config, @Nullable ProtectedVM pvm) {
        if (!isEnabled(config)) return 0;
        return guestPoolApplies(pvm) ? getGuestPoolMb(config) : 0;
    }

    /**
     * The {@code media-guest-mb} a VM will be passed at boot, for the huge-page preflight.
     *
     * <p>Only the guest pool is counted: the host pool is {@code consume_system_mem} and already
     * inside {@code --mem}, while the guest pool is memory taken beside the RAM. The protection
     * mode defaults the way a Gunyah VM's does, the only hypervisor the pools exist on. The
     * daemon's {@code boot_media_guest_mb} is the same rule.</p>
     */
    public static long bootMediaGuestMb(@NonNull DataItem config) {
        var pvm = optEnum(config, "protected_vm", ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        return Math.max(0, guestPoolMbFor(config, pvm));
    }
}
