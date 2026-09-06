// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

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
 * <p>Stored but not yet acted on: crosvm carries no virtio-media codec device, so the backends
 * read this and attach nothing. It lives here rather than in the tab so that the day the device
 * lands, the config it needs is already in every VM that asked for it.</p>
 */
public final class VpuConfig {
    public static final String KEY_ENABLED = "vpu_enabled";
    public static final String KEY_HOST_POOL_MB = "vpu_host_pool_mb";
    public static final String KEY_GUEST_POOL_MB = "vpu_guest_pool_mb";

    public static final int DEFAULT_HOST_POOL_MB = 256;
    public static final int DEFAULT_GUEST_POOL_MB = 128;

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
     * The guest pool size to pass to crosvm, or 0 for "do not create one".
     *
     * <p>0 is not a smaller pool, it is no {@code media_guest} node at all: with nothing in
     * /reserved-memory to find, the guest driver falls back to its stock behaviour of allocating
     * from system RAM.</p>
     */
    public static int guestPoolMbFor(@NonNull DataItem config, @Nullable ProtectedVM pvm) {
        return guestPoolApplies(pvm) ? getGuestPoolMb(config) : 0;
    }
}
