package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum GpuApi implements StringEnum {
    NONE(0, "none", R.string.nullptr),
    VULKAN(1, "vulkan", R.string.create_vm_gpu_api_vulkan),
    EGL(2, "egl", R.string.create_vm_gpu_api_egl),
    OPENGLES(3, "gles", R.string.create_vm_gpu_api_opengles),
    ANGLE(4, "angle", R.string.create_vm_gpu_api_angle);

    private final int value;
    private final String name;
    private final @StringRes int stringId;

    GpuApi(int value, String name, @StringRes int stringId) {
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

    @SuppressWarnings("unused")
    public static GpuApi fromValue(int value) {
        for (var v : values())
            if (v.value == value) return v;
        return NONE;
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
