package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * WHICH host driver serves the proxied calls, once {@link GpuMode} has decided what is proxied.
 *
 * <p>The two halves are the same question asked of two renderers, which is why they share a
 * control: with {@link GpuMode#OPENGL} virglrenderer needs a host GL context, and with
 * {@link GpuMode#VULKAN} gfxstream needs a host Vulkan driver.
 *
 * <p>{@link GpuMode#NATIVE} has no entry here on purpose. The DRM backend that answers is
 * compiled into virglrenderer for the device it runs on (KGSL on Adreno), so there is nothing
 * for a user to pick.
 *
 * <p>Persisted as {@code gpu_provider}; older configs carry the pre-split {@code gpu_api} and
 * are migrated by {@link #fromLegacyApi}.
 */
public enum GpuProvider implements StringEnum {
    NONE(0, "none", R.string.nullptr),
    // Host GL, for virglrenderer's OpenGL mode. Passed through as --gpu egl=/gles=/angle=.
    EGL(1, "egl", R.string.create_vm_gpu_api_egl),
    GLES(2, "gles", R.string.create_vm_gpu_api_opengles),
    ANGLE(3, "angle", R.string.create_vm_gpu_api_angle),
    // Host Vulkan, for gfxstream. Selects ANDROID_EMU_VK_LOADER_PATH: the SoC's stock Vulkan
    // HAL, the bundled Mesa turnip (Adreno), or Mesa PanVK (Mali) -- PanVK is not wired yet.
    VK_SYSTEM(4, "vulkan-system", R.string.create_vm_gpu_api_vulkan_system),
    VK_TURNIP(5, "vulkan-turnip", R.string.create_vm_gpu_api_vulkan_turnip),
    VK_PANVK(6, "vulkan-panvk", R.string.create_vm_gpu_api_vulkan_panvk);

    private final int value;
    private final String name;
    private final @StringRes int stringId;

    GpuProvider(int value, String name, @StringRes int stringId) {
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

    /** The provider implied by a pre-split {@code gpu_api} value. */
    @NonNull
    public static GpuProvider fromLegacyApi(@NonNull GpuApi api) {
        switch (api) {
            case EGL:           return EGL;
            case OPENGLES:      return GLES;
            case ANGLE:         return ANGLE;
            case VULKAN_SYSTEM: return VK_SYSTEM;
            case VULKAN_TURNIP: return VK_TURNIP;
            case VULKAN_PANVK:  return VK_PANVK;
            // Old plain VULKAN on virglrenderer only ever set --gpu vulkan=true; there is no
            // host-driver choice behind it, and on this build no venus to serve it either.
            default:            return NONE;
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
