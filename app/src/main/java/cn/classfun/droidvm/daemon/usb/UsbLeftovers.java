// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The interfaces a restore pass had to leave unbound, and whose they were.
 *
 * <p>Handing a device back to the host waits for the VMM's usbfs claim on each interface to go
 * and gives up after a while. On device, a VMM whose xHCI had died kept every claim until the
 * process itself exited minutes later, and the interfaces then stayed without a driver for
 * good: the pass that would have offered them to the host had already given up, and nothing
 * came back for them. This is what comes back. Every interface a wait gave up on is recorded
 * against the VM whose VMM held it, so that the VM's exit can retry exactly those, and a scan
 * that finds one of them free -- no driver at all, so the claim is gone -- can retry it
 * whenever that happens. Pure, and every call is made under the manager's lock; the waiting
 * and the probing are the manager's.</p>
 */
final class UsbLeftovers {
    /** What a scan found among the recorded interfaces. */
    static final class Scan {
        /** Interfaces nothing holds any more: the claim is gone, so these get their try. */
        final List<String> free = new ArrayList<>();
        /** Interfaces a host driver holds again, and that driver: someone else gave them back. */
        final LinkedHashMap<String, String> reclaimed = new LinkedHashMap<>();
    }

    /** The driver an interface shows while the VMM holds it: the claim that is being waited out. */
    private static final String DRIVER_USBFS = "usbfs";

    /** Interface name to the id of the VM that held it, in the order they were left. */
    private final LinkedHashMap<String, String> vmByInterface = new LinkedHashMap<>();

    boolean isEmpty() {
        return vmByInterface.isEmpty();
    }

    int size() {
        return vmByInterface.size();
    }

    /** A restore pass gave up on [iface] while [vmId]'s VMM still claimed it. */
    void leave(@NonNull String iface, @NonNull String vmId) {
        vmByInterface.put(iface, vmId);
    }

    /** Whether [iface] is recorded at all. */
    boolean contains(@NonNull String iface) {
        return vmByInterface.containsKey(iface);
    }

    /** The interfaces [vmId] left behind, oldest first. A query: they stay recorded. */
    @NonNull
    List<String> leftBy(@NonNull String vmId) {
        var result = new ArrayList<String>();
        for (var entry : vmByInterface.entrySet())
            if (vmId.equals(entry.getValue())) result.add(entry.getKey());
        return result;
    }

    /**
     * Takes [ifaces] off the record for a try, and says which VM each was left by. One that is
     * not recorded (any more) is not in the answer: another trigger got to it first.
     */
    @NonNull
    Map<String, String> take(@NonNull Collection<String> ifaces) {
        var result = new LinkedHashMap<String, String>();
        for (var iface : ifaces) {
            var vm = vmByInterface.remove(iface);
            if (vm != null) result.put(iface, vm);
        }
        return result;
    }

    /**
     * Reads a scan, given as every interface it saw mapped to its driver ({@code ""} for none),
     * against the record. An interface with no driver is free and is reported for a try; one
     * still showing the VMM's claim is not ready and stays; one a host driver holds is forgotten
     * -- it was given back some other way, and there is nothing left to do for it. An interface
     * the scan did not see stays too: whether its device is gone is the unplug's news to bring.
     */
    @NonNull
    Scan scan(@NonNull Map<String, String> drivers) {
        var result = new Scan();
        var it = vmByInterface.entrySet().iterator();
        while (it.hasNext()) {
            var iface = it.next().getKey();
            var driver = drivers.get(iface);
            if (driver == null || DRIVER_USBFS.equals(driver)) continue;
            if (driver.isEmpty()) {
                result.free.add(iface);
                continue;
            }
            result.reclaimed.put(iface, driver);
            it.remove();
        }
        return result;
    }

    /** The device at [sysfs] is gone, and its interfaces with it. Returns what was dropped. */
    @NonNull
    List<String> forgetDevice(@NonNull String sysfs) {
        var dropped = new ArrayList<String>();
        var it = vmByInterface.keySet().iterator();
        while (it.hasNext()) {
            var iface = it.next();
            if (!sysfs.equals(deviceOf(iface))) continue;
            dropped.add(iface);
            it.remove();
        }
        return dropped;
    }

    /** The device an interface belongs to: {@code 1-1.2.2:1.0} is {@code 1-1.2.2}'s. */
    @NonNull
    static String deviceOf(@NonNull String iface) {
        var colon = iface.indexOf(':');
        return colon < 0 ? iface : iface.substring(0, colon);
    }
}
