// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The two kinds of write to {@code /sys/bus/usb/drivers_probe}, which are not the same ask and
 * do not bind the same thing.
 *
 * <p>A DEVICE probe -- the device's own name, {@code 1-1.3} -- binds {@code usb_generic_driver},
 * and that driver is what CHOOSES AND SETS the configuration: {@code generic_probe} calls
 * {@code usb_choose_configuration} and then {@code usb_set_configuration}, and only that call
 * publishes the interface directories. Enumeration reads every descriptor whatever the gate
 * says, but the choosing is a probe like any other, so a device that came up while the gate was
 * shut has no configuration and no interfaces at all -- measured on the phone: the camera
 * appeared as {@code 1-1.3} with an empty {@code bConfigurationValue}, an empty
 * {@code bNumInterfaces} and not one interface under it. Under the shut gate no interface driver
 * follows the generic one, which is exactly the state a VM wants: the interfaces exist, they are
 * idle, and the configuration is the one the host would have chosen.</p>
 *
 * <p>An INTERFACE probe -- one interface's name, {@code 1-1.3:1.0} -- binds the real host driver:
 * usbhid, uvcvideo, usb-storage, and for a hub the {@code hub} driver, which is what makes its
 * ports be scanned again and its whole subtree enumerate. It is the only one of the two that can
 * revive a subtree, and it can do nothing at all for a device that has no interfaces yet.</p>
 *
 * <p>So the host gets a device by both, in that order, and a VM gets one by the first alone.
 * Free of android.*, of the shell and of the log on purpose: the write goes through a
 * {@link Writer} the manager supplies and the complaining is the caller's, which leaves the order
 * the two kinds happen in -- the thing that was wrong -- something a unit test can watch.</p>
 */
final class UsbProbe {
    /**
     * How long a device that has just been probed is given to publish its interfaces. Setting a
     * configuration re-reads the descriptors and registers an interface device for each, which
     * took about a second on the test phone when an {@code authorized} write asked for it; a
     * device probe asks for the same work, and a caller that looked straight away would find a
     * device with nothing to hand over and nothing to probe.
     */
    static final long INTERFACES_TIMEOUT_MS = 3000;
    static final long INTERFACES_POLL_MS = 100;

    /** One write to the bus's {@code drivers_probe}: the manager's, so nothing here shells out. */
    interface Writer {
        void probe(@NonNull String name);
    }

    private final File root;
    private final Writer writer;
    private final long timeoutMs;
    private final long pollMs;

    UsbProbe(@NonNull File root, @NonNull Writer writer) {
        this(root, writer, INTERFACES_TIMEOUT_MS, INTERFACES_POLL_MS);
    }

    /** As above with the wait shortened, for a test that wants the timeout and not the waiting. */
    UsbProbe(@NonNull File root, @NonNull Writer writer, long timeoutMs, long pollMs) {
        this.root = root;
        this.writer = writer;
        this.timeoutMs = timeoutMs;
        this.pollMs = pollMs;
    }

    /**
     * Whether a configuration is set for [sysfs], which is whether the generic driver has run for
     * it: the kernel prints {@code bConfigurationValue} from the active configuration and prints
     * nothing when there is none. That is the same question as "does this device have
     * interfaces", asked of the device itself rather than of the directory listing, and it is
     * what decides whether a device probe is owed.
     */
    boolean isConfigured(@NonNull String sysfs) {
        return !read(new File(root, sysfs), "bConfigurationValue").isEmpty();
    }

    /** The interfaces of [sysfs] as sysfs lists them now, sorted; none once the device is gone. */
    @NonNull
    List<String> interfacesOf(@NonNull String sysfs) {
        var names = new ArrayList<String>();
        var entries = root.listFiles();
        if (entries == null) return names;
        // Not "<sysfs>:" for every device: a root hub is usb3 and its interface is 3-0:1.0.
        var prefix = UsbHostDevice.interfacePrefix(sysfs);
        for (var entry : entries)
            if (entry.getName().startsWith(prefix)) names.add(entry.getName());
        Collections.sort(names);
        return names;
    }

    /** Basename of [name]'s {@code driver} symlink, {@code ""} when nothing holds it. */
    @NonNull
    String driverOf(@NonNull String name) {
        try {
            var target = Files.readSymbolicLink(new File(root, name).toPath().resolve("driver"));
            var leaf = target.getFileName();
            return leaf == null ? "" : leaf.toString();
        } catch (IOException | UnsupportedOperationException e) {
            return "";
        }
    }

    /**
     * The DEVICE probe: binds the generic driver to [sysfs] so that it has a configuration and
     * publishes its interfaces. Returns whether it wrote anything.
     *
     * <p>A device that already has a configuration is left alone. The generic driver is bound to
     * it, so the probe would decide nothing, and the write would put a line in the log for every
     * pass over every device the host already has -- which is most of them, most of the time.</p>
     *
     * <p>The question is the configuration and not the device's own {@code driver} link, which
     * is what an older shape of this asked, and there is one device the two answers differ for:
     * one coming back from a VM, which the phone showed as configured with no device driver
     * bound (1-1.3, its interfaces intact, after the guest let go). Asking about the driver
     * would re-bind the generic driver and have it choose the configuration again, tearing the
     * interfaces down and registering them afresh; asking about the configuration keeps the one
     * that is set and offers those interfaces as they stand. For a single-configuration device
     * -- everything on the rig -- the two end in the same place, and not tearing an interface
     * down under whoever is about to be given it is the safer of the two.</p>
     */
    boolean probeDevice(@NonNull String sysfs) {
        if (isConfigured(sysfs)) return false;
        writer.probe(sysfs);
        return true;
    }

    /** The INTERFACE probe, for one interface a caller has already decided about. */
    void probeInterface(@NonNull String iface) {
        writer.probe(iface);
    }

    /**
     * The INTERFACE probe over every interface of [sysfs] that nothing holds, which is what
     * offers a device to the host drivers. Returns how many it wrote.
     *
     * <p>An interface something already holds is skipped: that is what makes a pass over a device
     * the host already has cost nothing and log nothing, and it is what keeps this from writing
     * at a usbfs claim, where a probe does nothing anyway.</p>
     */
    int probeInterfaces(@NonNull String sysfs) {
        var probed = 0;
        for (var name : interfacesOf(sysfs)) {
            if (!driverOf(name).isEmpty()) continue;
            probeInterface(name);
            probed++;
        }
        return probed;
    }

    /**
     * The host action, whole: the device first when it has no configuration, then every free
     * interface. Returns how many writes it made, which is what tells a caller whether anything
     * was owed at all.
     *
     * <p>The order is the point, and the device half is not optional. A device the gate left
     * unconfigured has no interface for the second half to walk, so an interfaces-only host
     * action does exactly nothing for it -- which is how a hub that came up under the gate stayed
     * dead on the phone, taking its whole subtree with it.</p>
     *
     * <p>The wait between the two halves is {@link #configureForVm}'s, for the same reason it
     * takes one: publishing an interface creates and removes no {@code /dev/bus/usb} node, so a
     * host action that walked an empty list would do half its job with no second chance coming
     * -- nothing fires the watch, no difference is found, and no pass follows. On the phone the
     * device write was synchronous every time (the probe of 1-1.2 returned and 1-1.2:1.0 was
     * written in the same millisecond), so this costs nothing in the case that was measured;
     * what it buys is that a kernel which defers the generic driver's probe cannot leave a hub
     * with its subtree dark until some unrelated plug happens along.</p>
     */
    int giveToHost(@NonNull String sysfs) {
        var probed = 0;
        if (probeDevice(sysfs)) {
            probed++;
            awaitInterfaces(sysfs, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        }
        // Read after the write and the wait, never before: the interfaces the loop walks are the
        // ones the device probe has just created. Whether the wait gave up is not asked -- a
        // device that published nothing has nothing to walk, and the count says what was done.
        return probed + probeInterfaces(sysfs);
    }

    /**
     * The VM action's precondition: [sysfs] must have a configuration before it is handed over.
     * Returns whether it has interfaces now.
     *
     * <p>A VMM claims interfaces, so a device with none is handed over as a bare device and the
     * GUEST chooses the configuration. On the phone that turned the camera into a device whose
     * interface 0 read class FF instead of 14, so uvcvideo never bound in the guest and there was
     * no {@code /dev/video0}. Probing the device here means the configuration a VM gets is the
     * one the host would have chosen, and -- the gate still being shut -- no host driver takes
     * the interfaces on the way past.</p>
     */
    boolean configureForVm(@NonNull String sysfs) {
        // The common case, and the whole cost of it: a device the host or an earlier pass has
        // already had the generic driver bound to needs nothing and waits for nothing.
        if (!interfacesOf(sysfs).isEmpty()) return true;
        probeDevice(sysfs);
        return awaitInterfaces(sysfs, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
    }

    /**
     * Waits until [sysfs] publishes an interface or [deadlineNanos] passes, and says whether it
     * has one now. A device that has gone meanwhile answers false, having no interfaces: it is
     * for the caller to tell that apart from a device that never came back, and to say so,
     * because only one of the two is worth complaining about.
     */
    boolean awaitInterfaces(@NonNull String sysfs, long deadlineNanos) {
        while (interfacesOf(sysfs).isEmpty()) {
            if (!new File(root, sysfs).isDirectory()) return false;
            if (System.nanoTime() - deadlineNanos >= 0) return false;
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** One sysfs attribute of [dir], trimmed; {@code ""} when it is missing or unreadable. */
    @NonNull
    private static String read(@NonNull File dir, @NonNull String name) {
        try {
            var bytes = Files.readAllBytes(new File(dir, name).toPath());
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
