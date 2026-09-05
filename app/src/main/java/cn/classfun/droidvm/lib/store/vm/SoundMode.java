// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/** Which direction a virtio-snd device carries. One device is one direction. */
public enum SoundMode implements StringEnum {
    SPEAKER(R.string.edit_vm_sound_mode_speaker),
    MICROPHONE(R.string.edit_vm_sound_mode_microphone);

    private final int titleId;

    SoundMode(int titleId) {
        this.titleId = titleId;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    /** True when the host device feeds the guest rather than the other way round. */
    public boolean isInput() {
        return this == MICROPHONE;
    }
}
