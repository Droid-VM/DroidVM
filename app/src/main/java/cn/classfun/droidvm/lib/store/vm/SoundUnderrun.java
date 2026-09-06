// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * What the host device plays when the guest has not queued a period in time.
 *
 * <p>Silence is honest and maximally audible; continuing the waveform hides short holes, which
 * is the trade a shallow queue wants to make. Pairs with {@link SoundBuffer}: the lower the
 * latency, the more often there is something to conceal.</p>
 */
public enum SoundUnderrun implements StringEnum {
    /** A period of zeroes. Honest, and the most audible thing there is. */
    SILENCE(R.string.edit_vm_sound_underrun_silence, true),
    /**
     * Repeats the last pitch period. crosvm finds the period by autocorrelation over the tail of
     * the previous audio, repeats it in phase, fades it out across a few periods, and crossfades
     * real audio back in. Only 16-bit PCM is concealed; anything else falls back to silence.
     */
    REPEAT(R.string.edit_vm_sound_underrun_repeat, true),
    /**
     * Waveform Similarity Overlap-Add: searches for the best-matching window at each splice and
     * overlap-adds, rather than repeating one period unchanged. Costs more and does not lock to
     * a single pitch, so a long hole does not turn into a held note.
     */
    WSOLA(R.string.edit_vm_sound_underrun_wsola, true),
    /**
     * Linear-predictive extrapolation: fits an all-pole filter to the previous audio and excites
     * it to continue the signal, rather than reusing samples. What VoIP codecs do: the formants
     * come from the filter, so what does get repeated is the excitation, which loops far less
     * audibly than a waveform does.
     */
    LPC(R.string.edit_vm_sound_underrun_lpc, true);

    private final int titleId;
    private final boolean implemented;

    SoundUnderrun(int titleId, boolean implemented) {
        this.titleId = titleId;
        this.implemented = implemented;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    /** Hides the unimplemented modes from the picker; see {@code EnumPicker}. */
    @Override
    public boolean isDisplay() {
        return implemented;
    }
}
