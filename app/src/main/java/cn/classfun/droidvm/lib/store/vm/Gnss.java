// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * The VM's GNSS receiver ({@link PeripheralType#VIRTIO_GNSS}): one virtio-console port named
 * {@link #PORT_NAME} that streams NMEA 0183, the way a real receiver's serial line does.
 *
 * <p>A port rather than a device class of its own because there is no virtio GNSS device, and a
 * named serial port is what every guest already has a reader for: a Linux guest finds it as
 * {@code /dev/virtio-ports/org.droidvm.gnss.0} and hands it to gpsd. The daemon writes the
 * sentences ({@code GnssHub}); the app feeds it the phone's receiver or location from a
 * foreground service, since Android's location APIs are Java-only and gated on the app's uid
 * being foreground ({@code PeripheralForegroundService}).</p>
 */
public final class Gnss {
    /** The port's name in the guest. A contract with guest-side setup: do not rename. */
    public static final String PORT_NAME = "org.droidvm.gnss.0";
    public static final String KEY_SOURCE = "gnss_source";
    /** Whether the fix is marked simulated (GGA quality 8, RMC mode S). */
    public static final String KEY_MARK_SIMULATED = "gnss_mark_simulated";

    /** Bits of the foreground service's "which sources to run" extra. */
    public static final int SOURCE_RECEIVER = 1;
    public static final int SOURCE_LOCATION = 2;

    private Gnss() {
    }

    /** The VM's VirtIO GNSS row, or null. Only the first counts: the VM has one receiver. */
    @Nullable
    public static VMPeripheralConfig rowOf(@NonNull DataItem item) {
        for (var peripheral : VMPeripheralConfig.listOf(item))
            if (peripheral.getType() == PeripheralType.VIRTIO_GNSS) return peripheral;
        return null;
    }

    /** Whether the phone has a GNSS receiver at all; AUTO resolves on this. */
    public static boolean hostHasReceiver(@Nullable Context context) {
        if (context == null) return false;
        try {
            return context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        } catch (Exception e) {
            return false;
        }
    }

    /** The source bits {@code item}'s receiver needs on this phone, or 0 for a VM without one. */
    public static int sourceBitsOf(@NonNull DataItem item, boolean hostHasReceiver) {
        var row = rowOf(item);
        if (row == null) return 0;
        switch (row.getGnssSource().resolve(hostHasReceiver)) {
            case RECEIVER:
                return SOURCE_RECEIVER;
            case AUTO:
                return SOURCE_RECEIVER | SOURCE_LOCATION;
            default:
                return SOURCE_LOCATION;
        }
    }
}
