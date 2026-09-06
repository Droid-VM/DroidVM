// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * How much audio the guest driver should keep queued ahead of the device.
 *
 * <p>This is the latency knob, and it is a real trade rather than a tuning detail. Every period
 * the guest has not queued in time is a hole the device has to fill, and a hole is a click. On a
 * VM with no display driver -- where compositing and video decode run on the CPU -- scheduling
 * gaps of tens of milliseconds are ordinary, so a shallow queue clicks on exactly the content
 * that loads the guest hardest.</p>
 *
 * <p>The value reaches the driver through the device's vendor config block; a driver that does
 * not read it keeps its own default. It is counted in periods rather than milliseconds because
 * a period's duration is not known until the format is negotiated -- 2048 bytes is about 10.7ms
 * at 48kHz stereo 16-bit, and something else at any other rate, so a figure in milliseconds
 * could only ever be approximate while the count is exact.</p>
 *
 * <p>The deepest setting matches the driver's IO pool, which is what bounds how many periods it
 * can have in flight at once. Asking for more than the pool holds is quietly clamped, so the two
 * numbers are kept equal deliberately.</p>
 */
public enum SoundBuffer implements StringEnum {
    LOW(2, R.string.edit_vm_sound_buffer_low),
    NORMAL(6, R.string.edit_vm_sound_buffer_normal),
    SAFE(12, R.string.edit_vm_sound_buffer_safe);

    private final int packets;
    private final int titleId;

    SoundBuffer(int packets, int titleId) {
        this.packets = packets;
        this.titleId = titleId;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    /** Periods the guest should try to keep in flight. */
    public int getPackets() {
        return packets;
    }
}
