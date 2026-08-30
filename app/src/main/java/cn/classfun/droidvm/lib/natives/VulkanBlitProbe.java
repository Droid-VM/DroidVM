// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.natives;

import androidx.annotation.Nullable;

/**
 * Probes the platform's stock Vulkan driver for the extensions the native-display GPU blit needs.
 *
 * <p>The crosvm display bridge enables a fixed set of device extensions to import the virtio-gpu
 * scanout dmabuf and blit it into the SurfaceControl buffer; a driver missing them cannot run the
 * blit and the bridge falls back to a CPU copy. {@link cn.classfun.droidvm.lib.store.vm.GpuBlitProvider#SYSTEM}
 * points that bridge at the SoC's own driver, so this lets the editor tell the user up front which
 * extensions (if any) their platform lacks. It is a general capability check -- it inspects the
 * real driver's extension list, with no per-vendor assumptions.
 *
 * <p>The result is a property of the phone, not of any VM, so it is probed once and cached.
 */
public final class VulkanBlitProbe {
    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("vkprobe");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        LOADED = ok;
    }

    private static boolean probed;
    @Nullable private static String[] cached;

    private VulkanBlitProbe() {}

    /**
     * Required blit extensions the system Vulkan driver is missing.
     *
     * @return an empty array if a physical device supports all of them (SYSTEM blit is usable);
     *     a non-empty array naming the missing extensions; or {@code null} if the probe could not
     *     run at all (no loader / no device), i.e. capability is unknown.
     */
    @Nullable
    public static synchronized String[] missingBlitExtensions() {
        if (!probed) {
            String[] r = null;
            if (LOADED) {
                try {
                    r = nativeMissingBlitExtensions();
                } catch (Throwable t) {
                    r = null;
                }
            }
            cached = r;
            probed = true;
        }
        return cached == null ? null : cached.clone();
    }

    private static native String[] nativeMissingBlitExtensions();
}
