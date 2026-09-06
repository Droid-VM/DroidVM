// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.util.Objects;

import cn.classfun.droidvm.daemon.usb.UsbRules;

/**
 * One row of the management page: a device, who holds it now, and where the user has said it
 * should go instead.
 *
 * <p>The wanted target lives on the row rather than in a map beside the list because the list is
 * rebuilt from every {@code usb_host_changed}, which arrives whether or not anything is pending.
 * A choice is carried across a rebuild by {@link #key()}, which names the instance and not the
 * socket, so a device replugged while the page was open never inherits a choice made about the
 * one before it.</p>
 */
public final class UsbDeviceRow {
    public final UsbHostDeviceInfo device;
    /** Where the daemon says the device is, in the words a target speaks. */
    public final UsbDeviceTarget current;
    private UsbDeviceTarget wanted;

    public UsbDeviceRow(@NonNull UsbHostDeviceInfo device) {
        this.device = device;
        this.current = currentOf(device);
        this.wanted = current;
    }

    /** The instance key: the same socket with another device in it is another row. */
    @NonNull
    public String key() {
        return fmt("%s#%d", device.sysfs, device.devnum);
    }

    @NonNull
    public UsbDeviceTarget wanted() {
        return wanted;
    }

    public void want(@NonNull UsbDeviceTarget target) {
        wanted = target;
    }

    /**
     * Whether the apply has anything to do about this row.
     *
     * <p>Moving a device between two controllers of the same VM is not one of those things:
     * crosvm emulates the controller and takes no argument for it, so the daemon answers such a
     * request by leaving the device exactly where it is, and a row that kept asking for it would
     * stay pending for ever.</p>
     */
    public boolean isChanged() {
        if (wanted.kind == UsbRules.Target.VM && current.kind == UsbRules.Target.VM
            && Objects.equals(wanted.vmId, current.vmId)) return false;
        return !wanted.sameAs(current);
    }

    /**
     * Who holds the device now.
     *
     * <p>A device the host shows deauthorized reads as hidden whether or not this daemon
     * recorded hiding it: the row says where the device is, and the record only adds why.</p>
     */
    @NonNull
    private static UsbDeviceTarget currentOf(@NonNull UsbHostDeviceInfo device) {
        if (device.attachedVm != null)
            return UsbDeviceTarget.vm(device.attachedVm, device.attachedController);
        if (!device.authorized || device.sink != null) return UsbDeviceTarget.sink();
        return UsbDeviceTarget.host();
    }
}
