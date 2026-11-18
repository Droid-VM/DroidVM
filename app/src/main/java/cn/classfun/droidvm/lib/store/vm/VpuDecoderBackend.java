// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * What decodes video for a VM's hardware decoder device: crosvm's {@code --virtio-media
 * kind=decoder,backend=NAME}.
 *
 * <p>The guest sees the same V4L2 stateful decoder either way. What differs is the host side: the
 * platform's codec service, which every phone has, or the SoC's own codec driver, which one kind of
 * phone has and which decodes straight into the guest's buffers. A new driver (another SoC vendor's)
 * is a new constant here and a new entry in crosvm's table of direct drivers; nothing else in the app
 * knows the list.</p>
 */
public enum VpuDecoderBackend implements StringEnum {
    /**
     * Android's MediaCodec: the phone's hardware codecs through the platform's codec service. Works
     * on every phone and with every guest; each frame is converted and copied into the guest.
     */
    MEDIACODEC(R.string.edit_vm_vpu_decoder_backend_mediacodec, "mediacodec"),
    /**
     * The SoC's own driver for the sessions that ask for what only it gives (frames in decode
     * order, which a guest sets with the display-delay controls), MediaCodec for every other one
     * and on a phone crosvm has no driver for. A guest that does not know the direct path is served
     * exactly as with {@link #MEDIACODEC}.
     */
    AUTO(R.string.edit_vm_vpu_decoder_backend_auto, "auto"),
    /**
     * Qualcomm's msm_vidc V4L2 driver for every session whose codec it takes: frames are written
     * into the guest's own buffers with no copy, so the guest's driver has to lend buffers of its
     * own memory, and reads 10-bit video as P010. MediaCodec where the driver does not take the
     * stream or the phone has no such driver.
     */
    MSM_VIDC(R.string.edit_vm_vpu_decoder_backend_msm_vidc, "msm-vidc");

    private final int titleId;
    private final String crosvmName;

    VpuDecoderBackend(int titleId, @NonNull String crosvmName) {
        this.titleId = titleId;
        this.crosvmName = crosvmName;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    /** The value of crosvm's {@code backend=} key. */
    @NonNull
    public String crosvmName() {
        return crosvmName;
    }
}
