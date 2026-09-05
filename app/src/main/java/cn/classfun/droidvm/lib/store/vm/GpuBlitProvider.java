// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * WHICH host Vulkan driver performs the native display's GPU blit -- import the virtio-gpu scanout
 * dmabuf as a VkImage and blit it into the SurfaceControl buffer (the "1-gpu-copy" path in
 * devices/src/virtio/gpu). This is a separate axis from the render {@link GpuProvider}: the driver
 * that executes the guest's proxied calls and the driver that composites the scanout need not be
 * the same one. Only the accelerated scanout uses it at all -- VNC and simplefb present through
 * crosvm's CPU copy and ignore this entirely.
 *
 * <p>The Vulkan providers are peers, chosen and gated by the same rule: a provider is usable when
 * it exposes the raw-dmabuf-import extensions the blit needs (VK_EXT_external_memory_dma_buf et
 * al.). Turnip is not special -- it is simply the provider that passes on Adreno; the current
 * platforms just happen to all be Qualcomm. {@link #SYSTEM} defers to the SoC's stock driver
 * (offered only where a capability probe passes) and {@link #PANVK} to Mesa PanVK on Mali (not
 * wired yet). {@link #OFF} forces crosvm's CPU copy (GPU_DISPLAY_COPY_MODE=cpu), the universal
 * path every compositor accepts.
 *
 * <p>On any Vulkan provider the crosvm bridge probes the extensions itself and falls back to the
 * CPU copy if they are missing, so a wrong choice degrades rather than breaks.
 *
 * <p>Persisted as {@code display_blit_provider}; only meaningful with the native display on the
 * virtio-gpu backend.
 */
public enum GpuBlitProvider implements StringEnum {
    TURNIP(0, "turnip", R.string.create_vm_gpu_api_vulkan_turnip),
    PANVK(1, "panvk", R.string.create_vm_gpu_api_vulkan_panvk),
    SYSTEM(2, "system", R.string.create_vm_gpu_api_vulkan_system),
    OFF(3, "off", R.string.create_vm_display_blit_off);

    private final int value;
    private final String name;
    private final @StringRes int stringId;

    GpuBlitProvider(int value, String name, @StringRes int stringId) {
        this.value = value;
        this.name = name;
        this.stringId = stringId;
    }

    @SuppressWarnings("unused")
    public int getValue() {
        return value;
    }

    @SuppressWarnings("unused")
    public String getName() {
        return name;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static GpuBlitProvider fromValue(int value) {
        for (var v : values())
            if (v.value == value) return v;
        return TURNIP;
    }

    /** True for the providers that are wired today. Only PanVK is still unbuilt. */
    public boolean isImplemented() {
        return this != PANVK;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }
}
