package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum DisplayBackend implements StringEnum {
    NONE(0, "none", R.string.nullptr),
    SIMPLEFB(1, "simple", R.string.create_vm_display_backend_simplefb),
    VIRTIO_GPU(2, "virtio-gpu", R.string.create_vm_display_backend_virtio_gpu);

    private final int value;
    private final String name;
    private final @StringRes int stringId;

    DisplayBackend(int value, String name, @StringRes int stringId) {
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
