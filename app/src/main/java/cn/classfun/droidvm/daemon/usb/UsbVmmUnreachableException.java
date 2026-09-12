// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * The crosvm usb CLI could not connect to the VM's control socket, so the VMM never heard the
 * command.
 *
 * <p>An IOException, so every existing catch goes on treating it as the transport failure it is.
 * Its own type because, for an automatic attach, it is the one failure that says nothing about
 * the device: the VMM was not listening yet -- RUNNING is reported the moment the process is
 * spawned, seconds before its control loop is up -- and the pass should come back, not remember
 * the device as having failed.</p>
 */
public final class UsbVmmUnreachableException extends IOException {
    public UsbVmmUnreachableException(@NonNull String stderr) {
        super(fmt("control socket not reachable: %s", firstLine(stderr)));
    }

    @NonNull
    private static String firstLine(@NonNull String text) {
        var nl = text.indexOf('\n');
        return (nl < 0 ? text : text.substring(0, nl)).trim();
    }
}
