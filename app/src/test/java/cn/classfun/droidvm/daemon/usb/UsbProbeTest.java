// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The two kinds of probe, over a temporary directory shaped like {@code /sys/bus/usb/devices}
 * and with the kernel's half of the bargain played by the test's own writer: a DEVICE probe
 * creates the interface directories, an INTERFACE probe binds a driver. That is what makes the
 * order the writes happen in visible, and the order is the whole of what was wrong -- an
 * interfaces-only host action found nothing to walk on a device the gate had left unconfigured.
 * A kernel that answers late plays the other half of it: the interfaces of a device probe are
 * what the interface probe walks, so a host action that did not wait for them would write half.
 */
public final class UsbProbeTest {
    /** Short enough that the refusal test is a blink rather than three seconds. */
    private static final long TIMEOUT_MS = 60;
    private static final long POLL_MS = 5;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** Every name written to drivers_probe, in the order it was written. */
    private final List<String> writes = new ArrayList<>();

    private File root() {
        return folder.getRoot();
    }

    private static void write(File dir, String name, String content) throws IOException {
        Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A device as the gate leaves one: enumerated, addressed, and with no configuration, so
     * {@code bConfigurationValue} prints nothing and there is not one interface directory. The
     * camera of the on-device run, read exactly like this.
     */
    private File unconfiguredDevice(String sysfs) throws IOException {
        var dev = new File(root(), sysfs);
        assertTrue(dev.mkdirs());
        write(dev, "bConfigurationValue", "\n");
        write(dev, "bNumInterfaces", "\n");
        return dev;
    }

    /** The same device once something has given it a configuration. */
    private File configuredDevice(String sysfs, int interfaces) throws IOException {
        var dev = unconfiguredDevice(sysfs);
        write(dev, "bConfigurationValue", "1\n");
        write(dev, "bNumInterfaces", fmt("%d\n", interfaces));
        for (var i = 0; i < interfaces; i++) addInterface(sysfs, i);
        return dev;
    }

    /** An interface directory, beside its device, which is where the bus lists it. */
    private void addInterface(String sysfs, int index) {
        assertTrue(new File(root(), fmt("%s:1.%d", sysfs, index)).mkdirs());
    }

    /** Points an interface's driver link at a directory named after the driver, as sysfs does. */
    private void bindDriver(String iface, String driver) throws IOException {
        var drivers = root().toPath().resolve("drivers");
        Files.createDirectories(drivers.resolve(driver));
        Files.createSymbolicLink(
            new File(root(), iface).toPath().resolve("driver"), drivers.resolve(driver));
    }

    /** A writer that only records: the kernel does nothing back. */
    private UsbProbe deadKernel() {
        return new UsbProbe(root(), writes::add, TIMEOUT_MS, POLL_MS);
    }

    /**
     * A writer whose DEVICE probe answers late, from another thread, the way a kernel that
     * defers the generic driver's probe would: the write returns at once and the configuration
     * and the interfaces appear [delayMs] later. An INTERFACE probe binds [driver] at once, as
     * the kernel's does.
     */
    private UsbProbe slowKernel(String sysfs, int interfaces, String driver, long delayMs) {
        return new UsbProbe(root(), name -> {
            writes.add(name);
            if (name.equals(sysfs)) {
                var late = new Thread(() -> {
                    try {
                        Thread.sleep(delayMs);
                        write(new File(root(), sysfs), "bConfigurationValue", "1\n");
                        for (var i = 0; i < interfaces; i++) addInterface(sysfs, i);
                    } catch (InterruptedException | IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                late.setDaemon(true);
                late.start();
            } else if (name.startsWith(fmt("%s:", sysfs))) {
                try {
                    bindDriver(name, driver);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }, TIMEOUT_MS, POLL_MS);
    }

    /**
     * A writer that answers the way the kernel does: a DEVICE probe binds the generic driver,
     * which chooses a configuration and publishes [interfaces] of them, and an INTERFACE probe
     * binds the driver [driver] to that one interface. Nothing else happens -- with the gate
     * shut, no interface driver follows the generic one by itself.
     */
    private UsbProbe kernel(String sysfs, int interfaces, String driver) {
        return new UsbProbe(root(), name -> {
            writes.add(name);
            try {
                if (name.equals(sysfs)) {
                    write(new File(root(), sysfs), "bConfigurationValue", "1\n");
                    for (var i = 0; i < interfaces; i++) addInterface(sysfs, i);
                } else if (name.startsWith(fmt("%s:", sysfs))) {
                    bindDriver(name, driver);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, TIMEOUT_MS, POLL_MS);
    }

    @Test
    public void aDeviceUnderTheShutGateHasNoConfigurationAndNoInterfaces() throws IOException {
        unconfiguredDevice("1-1.3");
        var probe = deadKernel();
        assertFalse(probe.isConfigured("1-1.3"));
        assertTrue(probe.interfacesOf("1-1.3").isEmpty());
    }

    @Test
    public void theHostActionProbesTheDeviceBeforeItsInterfaces() throws IOException {
        unconfiguredDevice("1-1.3");
        // The camera: four interfaces, none of which exists until the device itself is probed.
        var probe = kernel("1-1.3", 4, "uvcvideo");
        assertEquals(5, probe.giveToHost("1-1.3"));
        assertEquals(List.of("1-1.3", "1-1.3:1.0", "1-1.3:1.1", "1-1.3:1.2", "1-1.3:1.3"), writes);
        assertTrue(probe.isConfigured("1-1.3"));
    }

    @Test
    public void theHostActionOfTheOldShapeWouldHaveWrittenNothing() throws IOException {
        unconfiguredDevice("1-1.3");
        var probe = kernel("1-1.3", 4, "uvcvideo");
        // The interface half on its own -- what the daemon used to do -- has nothing to walk.
        assertEquals(0, probe.probeInterfaces("1-1.3"));
        assertTrue(writes.isEmpty());
    }

    @Test
    public void aConfiguredDeviceIsOfferedItsInterfacesAndNothingElse() throws IOException {
        configuredDevice("1-1.2", 1);
        var probe = kernel("1-1.2", 1, "hub");
        // The hub of the on-device run: the generic driver had it, so its device probe is not
        // owed, but its one interface was driverless and its whole subtree was gone with it.
        assertEquals(1, probe.giveToHost("1-1.2"));
        assertEquals(List.of("1-1.2:1.0"), writes);
        assertEquals("hub", probe.driverOf("1-1.2:1.0"));
    }

    @Test
    public void anInterfaceSomethingAlreadyHoldsIsLeftAlone() throws IOException {
        configuredDevice("1-1.2.2", 4);
        bindDriver("1-1.2.2:1.0", "snd-usb-audio");
        bindDriver("1-1.2.2:1.3", "usbhid");
        var probe = deadKernel();
        assertEquals(2, probe.giveToHost("1-1.2.2"));
        assertEquals(List.of("1-1.2.2:1.1", "1-1.2.2:1.2"), writes);
    }

    @Test
    public void aDeviceTheHostAlreadyHasWholeIsNotWrittenToAtAll() throws IOException {
        configuredDevice("1-1.4.1", 1);
        bindDriver("1-1.4.1:1.0", "usb-storage");
        var probe = deadKernel();
        // The steady state of most devices on the phone, and the reason a pass over them is
        // silent: nothing is owed, so nothing is written and nothing is logged.
        assertEquals(0, probe.giveToHost("1-1.4.1"));
        assertTrue(writes.isEmpty());
    }

    @Test
    public void aRootHubIsProbedThroughItsOwnInterfaceName() throws IOException {
        configuredDevice("usb1", 0);
        // The kernel calls a root hub usb1 and its interface 1-0:1.0, not usb1:1.0.
        assertTrue(new File(root(), "1-0:1.0").mkdirs());
        var probe = deadKernel();
        assertEquals(List.of("1-0:1.0"), probe.interfacesOf("usb1"));
        assertEquals(1, probe.giveToHost("usb1"));
        assertEquals(List.of("1-0:1.0"), writes);
    }

    @Test
    public void theHostActionWaitsForInterfacesADeviceProbeIsStillPublishing() throws IOException {
        unconfiguredDevice("1-1.2");
        // A hub whose generic driver probes late. Without the wait the interface half walks an
        // empty list, writes nothing, and nothing raises the question again: publishing an
        // interface moves no /dev/bus/usb node, so no watch fires and no pass follows.
        var probe = slowKernel("1-1.2", 1, "hub", TIMEOUT_MS / 4);
        assertEquals(2, probe.giveToHost("1-1.2"));
        assertEquals(List.of("1-1.2", "1-1.2:1.0"), writes);
        assertEquals("hub", probe.driverOf("1-1.2:1.0"));
    }

    @Test
    public void theHostActionGivesUpOnADeviceThatPublishesNothing() throws IOException {
        unconfiguredDevice("1-1.3");
        var probe = deadKernel();
        var started = System.nanoTime();
        // The device write and no more: there is nothing to offer the host, and the wait is the
        // bounded one the VM half takes rather than a worker stuck for good.
        assertEquals(1, probe.giveToHost("1-1.3"));
        assertEquals(List.of("1-1.3"), writes);
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS));
    }

    @Test
    public void aDeviceThatAlreadyHasAConfigurationIsNotWaitedFor() throws IOException {
        configuredDevice("1-1.2", 1);
        var probe = deadKernel();
        var started = System.nanoTime();
        // The steady state pays nothing: the wait belongs to a device probe, and a device that
        // has a configuration is not owed one.
        assertEquals(1, probe.giveToHost("1-1.2"));
        assertEquals(List.of("1-1.2:1.0"), writes);
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS));
    }

    @Test
    public void theVmActionProbesTheDeviceAndWaitsForTheInterfaces() throws IOException {
        unconfiguredDevice("1-1.3");
        var probe = kernel("1-1.3", 4, "uvcvideo");
        assertTrue(probe.configureForVm("1-1.3"));
        // The device and nothing else: the gate is still shut, so the interfaces the VMM is
        // about to claim are there and idle, and the configuration is the host's choice.
        assertEquals(List.of("1-1.3"), writes);
        assertEquals(4, probe.interfacesOf("1-1.3").size());
        for (var iface : probe.interfacesOf("1-1.3")) assertEquals("", probe.driverOf(iface));
    }

    @Test
    public void theVmActionRefusesADeviceWhoseInterfacesNeverAppear() throws IOException {
        unconfiguredDevice("1-1.3");
        var probe = deadKernel();
        assertFalse(probe.configureForVm("1-1.3"));
        // It asked before it refused: the refusal is about a device that cannot be configured,
        // not about one nobody tried to configure.
        assertEquals(List.of("1-1.3"), writes);
    }

    @Test
    public void theVmActionAsksNothingOfADeviceThatAlreadyHasInterfaces() throws IOException {
        configuredDevice("1-1.3", 4);
        var probe = deadKernel();
        assertTrue(probe.configureForVm("1-1.3"));
        assertTrue(writes.isEmpty());
    }

    @Test
    public void aDeviceThatWentWhileTheWaitRanIsNotWaitedOutInFull() throws IOException {
        var dev = unconfiguredDevice("1-1.3");
        var probe = deadKernel();
        assertTrue(new File(dev, "bConfigurationValue").delete());
        assertTrue(new File(dev, "bNumInterfaces").delete());
        assertTrue(dev.delete());
        var started = System.nanoTime();
        assertFalse(probe.configureForVm("1-1.3"));
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS));
    }
}
