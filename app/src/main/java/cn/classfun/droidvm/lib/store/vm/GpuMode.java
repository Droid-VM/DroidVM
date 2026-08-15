// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * WHERE the guest is intercepted -- the virtio-gpu context type, in plain terms.
 *
 * <p>This is the axis rutabaga actually has: a capset belongs to a renderer component, and the
 * component decides how much of the guest's graphics work crosses the boundary.
 *
 * <ul>
 *   <li>{@link #OPENGL} — GL calls are proxied. Guest runs mesa's virgl gallium driver;
 *       virglrenderer replays the command stream on a host GL context. Capset virgl2.
 *   <li>{@link #VULKAN} — Vulkan calls are proxied. On gfxstream that is its Vulkan
 *       component (capset gfxstream-vulkan); on virglrenderer it is venus (capset venus). Either
 *       way the host side needs an ICD, which is what {@link GpuProvider}'s VK_* row picks.
 *   <li>{@link #NATIVE} — only kernel ioctls are proxied. The guest runs its REAL driver
 *       (turnip over vdrm here) and the host translates the DRM uAPI. Capset drm; which DRM
 *       backend answers is a property of the device, KGSL on Adreno.
 * </ul>
 *
 * <p>Persisted as {@code gpu_mode}; older configs carry the pre-split {@code gpu_api} and are
 * migrated by {@link #fromLegacyApi}.
 */
public enum GpuMode implements StringEnum {
    NONE(0, "none", R.string.nullptr),
    OPENGL(1, "opengl", R.string.create_vm_gpu_mode_opengl),
    VULKAN(2, "vulkan", R.string.create_vm_gpu_mode_vulkan),
    NATIVE(3, "native", R.string.create_vm_gpu_mode_native);

    private final int value;
    private final String name;
    private final @StringRes int stringId;

    GpuMode(int value, String name, @StringRes int stringId) {
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

    /** The mode implied by a pre-split {@code gpu_api} value. */
    @NonNull
    public static GpuMode fromLegacyApi(@NonNull GpuApi api) {
        switch (api) {
            case EGL:
            case OPENGLES:
            case ANGLE:
                return OPENGL;
            case VULKAN:
            case VULKAN_SYSTEM:
            case VULKAN_TURNIP:
            case VULKAN_PANVK:
                return VULKAN;
            case DRM2KGSL:
                return NATIVE;
            default:
                return NONE;
        }
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }

    @Override
    public boolean isDisplay() {
        return stringId != R.string.nullptr;
    }
}
