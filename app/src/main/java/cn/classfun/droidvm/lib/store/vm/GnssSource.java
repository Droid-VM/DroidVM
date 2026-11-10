// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Where a VirtIO GNSS row's receiver gets what it reports.
 *
 * <p>Two real answers and a default that picks between them. Either way the guest sees one GNSS
 * receiver speaking NMEA; what differs is how much of it is the phone's.</p>
 */
public enum GnssSource implements StringEnum {
    /** The receiver when the phone has one, the location otherwise ({@link #resolve}). */
    AUTO(R.string.edit_vm_gnss_source_auto),
    /**
     * The phone's GNSS receiver: its fixes, and every satellite of every system it tracks (GPS,
     * GLONASS, Galileo, BeiDou, QZSS, NavIC: whatever the phone has), with their signal strength,
     * place in the sky and part in the solution. Keeps the receiver powered while the VM runs.
     */
    RECEIVER(R.string.edit_vm_gnss_source_receiver),
    /**
     * The phone's location only (its fused provider: Wi-Fi, cells, GNSS when it is on anyway),
     * reported with a fixed set of GPS satellites made up to carry it. Works on a phone with no
     * GNSS receiver, and costs about what the phone's own location does.
     */
    LOCATION(R.string.edit_vm_gnss_source_location);

    private final int titleId;

    GnssSource(int titleId) {
        this.titleId = titleId;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    /** What this choice means on a phone that has, or has no, GNSS receiver. Never AUTO. */
    public GnssSource resolve(boolean hostHasReceiver) {
        if (this != AUTO) return this;
        return hostHasReceiver ? RECEIVER : LOCATION;
    }
}
