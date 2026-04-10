package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum GpuBackend implements StringEnum {
    NONE(0, "none", R.string.nullptr),
    GPU_2D(1, "2d", R.string.create_vm_gpu_backend_2d),
    GPU_VIRGLRENDERER(2, "virglrenderer", R.string.create_vm_gpu_backend_virglrenderer),
    GPU_GFXSTREAM(3, "gfxstream", R.string.create_vm_gpu_backend_gfxstream);

    private final int value;
    private final String name;
    private final @StringRes int stringId;

    GpuBackend(int value, String name, @StringRes int stringId) {
        this.value = value;
        this.name = name;
        this.stringId = stringId;
    }

    @SuppressWarnings("unused")
    public int getValue() {
        return value;
    }

    public String getName() {
        return name;
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
