// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * One message off the kernel's uevent socket, as much of it as this daemon reads.
 *
 * <p>The reason there is a second event source at all is that binding or unbinding a driver moves
 * nothing: the interface directory stays where it was, the device node under {@code /dev/bus/usb}
 * stays where it was, and only the {@code driver} symlink comes or goes. The inotify watch sees
 * none of it. The kernel does report it -- {@code KOBJ_BIND} and {@code KOBJ_UNBIND} since 4.12 --
 * and only here. Measured on the phone: unbinding usbhid from {@code 1-1.6:1.0} left the node set
 * byte-identical while pushing {@code /sys/kernel/uevent_seqnum} by five, and the daemon, watching
 * only nodes, logged nothing at all.</p>
 *
 * <p>A message is NUL-separated: the first field is {@code <action>@<devpath>} and the rest are
 * {@code KEY=VALUE}. Free of {@code android.*} so the parsing is a unit test rather than a
 * device run.</p>
 */
final class UsbUevent {
    /** The subsystem this daemon has anything to say about. */
    private static final String SUBSYSTEM_USB = "usb";

    /** {@code bind}, {@code unbind}, {@code add}, {@code remove}, {@code change}. */
    @NonNull
    public final String action;
    /**
     * The device's sysfs leaf: {@code 1-1.6:1.0} for an interface, {@code 1-1.6} or {@code usb1}
     * for a device. The same name {@code /sys/bus/usb/devices} lists it under, which is what the
     * rest of this package speaks.
     */
    @NonNull
    public final String name;
    /** {@code usb_device} or {@code usb_interface}; empty when the kernel did not say. */
    @NonNull
    public final String devtype;
    /** The driver that was bound, on a {@code bind}; empty on everything else. */
    @NonNull
    public final String driver;

    private UsbUevent(@NonNull String action, @NonNull String name, @NonNull String devtype,
                      @NonNull String driver) {
        this.action = action;
        this.name = name;
        this.devtype = devtype;
        this.driver = driver;
    }

    /**
     * Reads one datagram, or null when it says nothing about USB -- which is most of them, and
     * the reason this is the first thing the reader does with a message.
     */
    @Nullable
    static UsbUevent parse(@NonNull byte[] message, int length) {
        var action = "";
        var devpath = "";
        var devtype = "";
        var driver = "";
        var subsystem = "";
        var start = 0;
        for (var i = 0; i <= length; i++) {
            if (i != length && message[i] != 0) continue;
            if (i > start) {
                var field = new String(message, start, i - start, StandardCharsets.UTF_8);
                if (field.startsWith("ACTION=")) action = value(field);
                else if (field.startsWith("DEVPATH=")) devpath = value(field);
                else if (field.startsWith("DEVTYPE=")) devtype = value(field);
                else if (field.startsWith("DRIVER=")) driver = value(field);
                else if (field.startsWith("SUBSYSTEM=")) subsystem = value(field);
            }
            start = i + 1;
        }
        // The first field is "<action>@<devpath>" and every message has it, but the ACTION and
        // DEVPATH keys carry the same two values and are what the kernel documents, so the
        // header is not read at all rather than read as a second opinion.
        if (!SUBSYSTEM_USB.equals(subsystem)) return null;
        if (action.isEmpty() || devpath.isEmpty()) return null;
        return new UsbUevent(action, leaf(devpath), devtype, driver);
    }

    @NonNull
    private static String value(@NonNull String field) {
        return field.substring(field.indexOf('=') + 1);
    }

    /** The last path element of a DEVPATH, which is the name sysfs lists the device under. */
    @NonNull
    private static String leaf(@NonNull String devpath) {
        var slash = devpath.lastIndexOf('/');
        return slash < 0 ? devpath : devpath.substring(slash + 1);
    }

    /**
     * Whether this is a driver coming or going, which is the class of change nothing else
     * reports. An add or a remove moves a node and the inotify watch has already raised it; both
     * still schedule the same rescan, because a duplicate costs one scan that finds no
     * difference and a missed one costs a device nobody ever decides about again.
     */
    boolean isDriverChange() {
        return "bind".equals(action) || "unbind".equals(action);
    }

    @NonNull
    @Override
    public String toString() {
        return driver.isEmpty()
            ? fmt("%s %s", action, name)
            : fmt("%s %s (%s)", action, name, driver);
    }
}
