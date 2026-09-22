// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The generation a device enumerated at, from the megabits per second sysfs reports.
 *
 * <p>10 Gbps is really 3.1 and 20 Gbps is 3.2 Gen 2x2, but a person looking at a card wants to
 * know whether the device came up on the fast bus or the slow one, and that is the line between
 * 2.0 and 3.0. Anything the kernel reports that is not one of the five known rates says nothing
 * rather than guessing, and the card then shows the name alone.</p>
 */
public final class UsbSpeed {
    private UsbSpeed() {
    }

    /** {@code "3.0"}, {@code "2.0"}, {@code "1.1"}, {@code "1.0"}, or "" when unknown. */
    @NonNull
    public static String generationOf(@Nullable String speed) {
        if (speed == null) return "";
        switch (speed.trim()) {
            case "20000":
            case "10000":
            case "5000":
                return "3.0";
            case "480":
                return "2.0";
            case "12":
                return "1.1";
            case "1.5":
                return "1.0";
            default:
                return "";
        }
    }
}
