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
    /**
     * Whether the user has answered for this row. Where the device already is is not an answer:
     * every row would otherwise be a request the moment the page opened.
     */
    private boolean picked = false;

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

    /**
     * Records where the user asked for the device to go, and says whether that was an answer
     * this page can give.
     *
     * <p>Moving a device between two controllers of the VM that already holds it is not one:
     * crosvm emulates the controller and takes no argument for it, so the daemon leaves the
     * device exactly where it is, and a button that kept the pick would promise a move that
     * never happens and never clears.</p>
     */
    public boolean want(@NonNull UsbDeviceTarget target) {
        if (target.kind == UsbRules.Target.VM && current.kind == UsbRules.Target.VM
            && Objects.equals(target.vmId, current.vmId)) return false;
        wanted = target;
        picked = true;
        return true;
    }

    /**
     * Whether the apply has anything to do about this row.
     *
     * <p>Asking for the target the device already has is one of those things, as long as nothing
     * pins it there yet: that request is how a state the rules made becomes the user's own, and
     * the pin it leaves behind is the whole of what keeps the next rules pass from undoing it.
     * A device that is already pinned has nothing more to gain from being asked again.</p>
     */
    public boolean isChanged() {
        if (!picked) return false;
        if (wanted.sameAs(current)) return !device.held;
        return true;
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
