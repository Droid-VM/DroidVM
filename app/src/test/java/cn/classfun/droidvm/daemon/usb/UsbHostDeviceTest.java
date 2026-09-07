// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Covers the sysfs parsing, over a temporary directory shaped like the real one.
 *
 * <p>Nothing asserts on what {@code toJson()} put in the object: unit tests run against the
 * stubbed android.jar, where {@code org.json} does nothing and reports nothing (see the
 * {@code isReturnDefaultValues} comment in app/build.gradle.kts). Calling it still proves the
 * mapping compiles and runs over every field, and {@code toMap()} -- which is what it iterates --
 * carries the wire shape that a client depends on.</p>
 */
public final class UsbHostDeviceTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static void write(File dir, String name, String content) throws IOException {
        Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /** A USB mass-storage stick, the way the kernel describes one. */
    private File writeFlashDrive(File root) throws IOException {
        var dev = new File(root, "1-1.1");
        assertTrue(dev.mkdirs());
        write(dev, "idVendor", "090c\n");
        write(dev, "idProduct", "1000\n");
        write(dev, "busnum", "1\n");
        write(dev, "devnum", "4\n");
        write(dev, "bDeviceClass", "00\n");
        write(dev, "speed", "480\n");
        write(dev, "manufacturer", "Silicon Motion\n");
        write(dev, "product", "USB DISK\n");
        write(dev, "serial", "0123456789ABCDEF\n");
        var iface = new File(dev, "1-1.1:1.0");
        assertTrue(iface.mkdirs());
        write(iface, "bInterfaceClass", "08\n");
        write(iface, "bInterfaceSubClass", "06\n");
        write(iface, "bInterfaceProtocol", "50\n");
        return dev;
    }

    /** Points the interface's driver link at a directory named after the driver, as sysfs does. */
    private void bindDriver(File ifaceDir, String driver) throws IOException {
        var drivers = folder.getRoot().toPath().resolve("drivers");
        Files.createDirectories(drivers.resolve(driver));
        Files.createSymbolicLink(ifaceDir.toPath().resolve("driver"), drivers.resolve(driver));
    }

    @Test
    public void readsEveryFieldOfAMassStorageDevice() throws Exception {
        var root = folder.newFolder("sysfs");
        var dev = writeFlashDrive(root);
        bindDriver(new File(dev, "1-1.1:1.0"), "usb-storage");

        var device = UsbHostDevice.fromSysfs(dev, "/dev/bus/usb");
        assertEquals("1-1.1", device.sysfs);
        assertEquals(1, device.busnum);
        assertEquals(4, device.devnum);
        assertEquals("/dev/bus/usb/001/004", device.node);
        assertEquals("090c", device.vid);
        assertEquals("1000", device.pid);
        assertEquals("Silicon Motion", device.manufacturer);
        assertEquals("USB DISK", device.product);
        assertEquals("0123456789ABCDEF", device.serial);
        assertEquals("480", device.speed);
        assertEquals("00", device.deviceClass);
        assertEquals(1, device.interfaces.size());
        var iface = device.interfaces.get(0);
        assertEquals("1-1.1:1.0", iface.name);
        assertEquals("08", iface.cls);
        assertEquals("06", iface.subcls);
        assertEquals("50", iface.proto);
        assertEquals("usb-storage", iface.driver);
        assertFalse(device.isHub());
        assertEquals(UsbHostDevice.State.HOSTUSE, device.state());
        assertNotNull(device.toJson());
    }

    @Test
    public void missingOptionalFilesReadAsEmpty() throws Exception {
        var root = folder.newFolder("sysfs");
        var dev = writeFlashDrive(root);
        assertTrue(new File(dev, "manufacturer").delete());
        assertTrue(new File(dev, "serial").delete());

        var device = UsbHostDevice.fromSysfs(dev, "/dev/bus/usb");
        assertEquals("", device.manufacturer);
        assertEquals("", device.serial);
        assertEquals("USB DISK", device.product);
    }

    @Test
    public void theRuleIdIsVidPidAndSerial() throws Exception {
        var root = folder.newFolder("sysfs");
        var dev = writeFlashDrive(root);

        assertEquals("090c:1000:0123456789ABCDEF", UsbHostDevice.fromSysfs(dev, "/dev/bus/usb").id);
        assertTrue(new File(dev, "serial").delete());
        assertEquals("090c:1000", UsbHostDevice.fromSysfs(dev, "/dev/bus/usb").id);
    }

    @Test
    public void theRuleIdLowercasesTheHexAndKeepsTheSerial() {
        assertEquals("090c:1000:ABC-def", UsbHostDevice.deriveId("090C", "1000", "ABC-def"));
        assertEquals("0bda:8153", UsbHostDevice.deriveId("0BDA", "8153", ""));
    }

    @Test
    public void thePortIsTheSysfsNameWithoutItsBus() throws Exception {
        var root = folder.newFolder("sysfs");
        assertEquals("1.1", UsbHostDevice.fromSysfs(writeFlashDrive(root), "/dev/bus/usb").port);
        assertEquals("1.2.2", UsbHostDevice.derivePort("1-1.2.2"));
        assertEquals("1.4", UsbHostDevice.derivePort("2-1.4"));
        // One socket, two buses: a USB2 device enumerates on bus 1 and a USB3 one on bus 2, and
        // only with the bus gone do the two name the same place.
        assertEquals(UsbHostDevice.derivePort("1-1.4"), UsbHostDevice.derivePort("2-1.4"));
        assertEquals("3", UsbHostDevice.derivePort("1-3"));
    }

    @Test
    public void aDeviceClassOfNineIsAHub() throws Exception {
        var root = folder.newFolder("sysfs");
        var dev = writeFlashDrive(root);
        write(dev, "bDeviceClass", "09\n");

        assertTrue(UsbHostDevice.fromSysfs(dev, "/dev/bus/usb").isHub());
    }

    @Test
    public void authorizedIsReadFromSysfsAndAbsentMeansAuthorized() throws Exception {
        // Nothing writes this any more; it is read so that the daemon's start-up migration can
        // find a device an older build hid this way and give it back.
        var root = folder.newFolder("sysfs");
        var dev = writeFlashDrive(root);
        // A device nobody deauthorized: the file is there and says 1.
        write(dev, "authorized", "1\n");
        assertTrue(UsbHostDevice.fromSysfs(dev, "/dev/bus/usb").authorized);
        // What the older build's sink left behind: no configuration, no interfaces, and nothing
        // in the state model able to see it.
        write(dev, "authorized", "0\n");
        assertFalse(UsbHostDevice.fromSysfs(dev, "/dev/bus/usb").authorized);
        // A kernel or a device without the attribute is not a deauthorized one.
        assertTrue(new File(dev, "authorized").delete());
        assertTrue(UsbHostDevice.fromSysfs(dev, "/dev/bus/usb").authorized);
    }

    @Test
    public void aDeviceWithNothingBoundToItIsIdle() throws Exception {
        // What the autoprobe gate leaves behind: fully enumerated, interfaces and all, and not
        // one driver bound to any of them.
        var root = folder.newFolder("sysfs");
        var dev = writeFlashDrive(root);

        var device = UsbHostDevice.fromSysfs(dev, "/dev/bus/usb");
        assertEquals(1, device.interfaces.size());
        assertEquals("", device.interfaces.get(0).driver);
        assertEquals(UsbHostDevice.State.IDLE, device.state());
    }

    @Test
    public void aUsbfsClaimIsAVmUsingTheDeviceAndOutranksAHostDriver() throws Exception {
        var root = folder.newFolder("sysfs");
        var dev = writeFlashDrive(root);
        bindDriver(new File(dev, "1-1.1:1.0"), "usbfs");
        assertEquals(UsbHostDevice.State.VMUSE,
            UsbHostDevice.fromSysfs(dev, "/dev/bus/usb").state());

        // Half claimed, which only happens while a VMM is dying: the live fd wins, because a
        // device with an owner may not be handed to anybody, and the leftovers are what take a
        // claim nobody owns any more off it.
        var second = new File(dev, "1-1.1:1.1");
        assertTrue(second.mkdirs());
        bindDriver(second, "usb-storage");
        assertEquals(UsbHostDevice.State.VMUSE,
            UsbHostDevice.fromSysfs(dev, "/dev/bus/usb").state());
    }

    @Test
    public void theWireShapeIsFixed() throws Exception {
        var root = folder.newFolder("sysfs");
        var dev = writeFlashDrive(root);
        bindDriver(new File(dev, "1-1.1:1.0"), "usb-storage");

        var map = UsbHostDevice.fromSysfs(dev, "/dev/bus/usb").toMap();
        assertEquals(Arrays.asList("sysfs", "id", "port", "busnum", "devnum", "node", "vid", "pid",
                "manufacturer", "product", "serial", "speed", "device_class", "state",
                "interfaces"),
            new ArrayList<>(map.keySet()));
        var interfaces = (List<?>) map.get("interfaces");
        assertNotNull(interfaces);
        assertEquals(1, interfaces.size());
        assertEquals(Arrays.asList("name", "class", "subclass", "protocol", "driver"),
            new ArrayList<>(((Map<?, ?>) interfaces.get(0)).keySet()));
    }

    @Test
    public void scanTakesEveryDeviceAndTheHubsWithThem() throws Exception {
        var root = folder.newFolder("sysfs");
        writeFlashDrive(root);
        // A hub: on the same bus, and the thing the stick hangs off.
        var hub = new File(root, "1-1");
        assertTrue(hub.mkdirs());
        write(hub, "idVendor", "05e3\n");
        write(hub, "idProduct", "0610\n");
        write(hub, "busnum", "1\n");
        write(hub, "devnum", "3\n");
        write(hub, "bDeviceClass", "09\n");
        // A root hub and an interface directory, both of which sit next to the devices.
        assertTrue(new File(root, "usb1").mkdirs());
        assertTrue(new File(root, "1-1.1:1.0").mkdirs());

        // The hub is in: one the gate left driverless is a whole subtree that never enumerates,
        // so the pass has to be able to see it and give it back. A root hub and an interface
        // directory are not devices and stay out.
        var devices = new UsbHostInventory(root.getAbsolutePath(), "/dev/bus/usb").scan();
        assertEquals(2, devices.size());
        assertEquals("1-1", devices.get(0).sysfs);
        assertTrue(devices.get(0).isHub());
        assertEquals("1-1.1", devices.get(1).sysfs);
    }
}
