// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * What encodes video for a VM's hardware encoder device: crosvm's {@code --virtio-media
 * kind=encoder,backend=NAME}, the decoder's key with the decoder's values ({@link VpuDecoderBackend}).
 *
 * <p>Unlike the decoder's, the choice asks nothing of the guest: whichever side encodes, the guest
 * gives tightly packed NV12 frames and gets the bitstream back. What differs is the host side: the
 * platform's codec service, or the SoC's own encoder driver, which takes the guest's bitrate and
 * key-frame requests as they are and encodes in one hardware pass.</p>
 */
public enum VpuEncoderBackend implements StringEnum {
    /** Android's MediaCodec: the phone's hardware encoders through the platform's codec service. */
    MEDIACODEC(R.string.edit_vm_vpu_decoder_backend_mediacodec, "mediacodec"),
    /**
     * The SoC's own encoder driver for every session it takes, MediaCodec for the rest and on a
     * phone crosvm has no driver for.
     */
    AUTO(R.string.edit_vm_vpu_decoder_backend_auto, "auto"),
    /**
     * Qualcomm's msm_vidc V4L2 encoder for every session it takes (H.264 and HEVC at a variable or
     * constant bitrate); MediaCodec for the rest. On a phone with the driver the same as
     * {@link #AUTO}, except that crosvm does not first check that the driver is there.
     */
    MSM_VIDC(R.string.edit_vm_vpu_decoder_backend_msm_vidc, "msm-vidc");

    private final int titleId;
    private final String crosvmName;

    VpuEncoderBackend(int titleId, @NonNull String crosvmName) {
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
