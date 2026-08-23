// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Kind of virtual peripheral attached to a VM -- one entry here is one device the guest sees.
 *
 * <p>The list deliberately names hardware rather than roles. An earlier version offered
 * "Speaker" and "Microphone", which reads well but does not survive contact with the devices:
 * a virtio-snd card is one direction with one host endpoint, while an Intel HDA codec is a
 * single card carrying both. Anything that maps roles onto devices has to guess, and the guess
 * is wrong for one of the two. Naming the device and putting the role inside it keeps the UI
 * and the command line the same shape.</p>
 */
public enum PeripheralType implements StringEnum {
    /** virtio-snd, one PCM direction per device. Served by an unprivileged vhost-user helper. */
    VIRTIO_SOUND(R.string.edit_vm_peripheral_type_virtio_sound, R.drawable.ic_speaker, true),
    /**
     * Intel HD Audio codec: one card, playback and capture together. Present so the model is
     * honest about what a guest could have -- Windows has an in-box driver for it, which
     * virtio-snd does not -- but crosvm emulates no HDA controller, so nothing can serve it yet.
     */
    INTEL_HDA(R.string.edit_vm_peripheral_type_intel_hda, R.drawable.ic_microphone, false);

    private final @StringRes int titleId;
    private final @DrawableRes int iconId;
    private final boolean available;

    PeripheralType(@StringRes int titleId, @DrawableRes int iconId, boolean available) {
        this.titleId = titleId;
        this.iconId = iconId;
        this.available = available;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    @DrawableRes
    public int getIconId() {
        return iconId;
    }

    /** False when nothing on the host can serve this device yet; the UI says so and the
     *  backends skip it rather than starting a VM that lies about its hardware. */
    public boolean isAvailable() {
        return available;
    }
}
