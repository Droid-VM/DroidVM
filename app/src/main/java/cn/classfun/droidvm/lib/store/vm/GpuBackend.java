// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Which renderer serves the guest's graphics, listed in the order the editor offers them: software
 * 2D first, then the two proxying renderers.
 *
 * <p>Declaration order is menu order (the picker walks the constants), so it is a UI decision, not
 * a storage one -- the config carries the constant's name ({@code Enums.optEnum}), never its
 * position, so reordering here does not touch a stored VM.</p>
 */
public enum GpuBackend implements StringEnum {
    NONE(0, "none", R.string.nullptr),
    GPU_2D(1, "2d", R.string.create_vm_gpu_backend_2d),
    GPU_GFXSTREAM(3, "gfxstream", R.string.create_vm_gpu_backend_gfxstream),
    GPU_VIRGLRENDERER(2, "virglrenderer", R.string.create_vm_gpu_backend_virglrenderer);

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
