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

/**
 * What one rescan calls a difference, over a temporary directory scanned twice. The listener is
 * called for a difference and for nothing else, so this is where the pass's trigger is decided:
 * an addition is not the only news there is, and the case that left the phone's tree dead -- a
 * device that lost its driver, or gained interfaces nothing holds -- has to read as one.
 */
public final class UsbHostInventoryTest {
    /** Hub class, as the kernel prints it in bDeviceClass and bInterfaceClass alike. */
    private static final String CLASS_HUB = "09";
    /** Video class: what the camera of the on-device run reads once it is configured. */
    private static final String CLASS_VIDEO = "0e";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static void write(File dir, String name, String content) throws IOException {
        Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /** A device directory, with the files every scan needs and no interface yet. */
    private File device(String sysfs, int devnum, String deviceClass) throws IOException {
        var dev = new File(folder.getRoot(), sysfs);
        assertTrue(dev.isDirectory() || dev.mkdirs());
        write(dev, "idVendor", "32e6\n");
        write(dev, "idProduct", "9221\n");
        write(dev, "busnum", "1\n");
        write(dev, "devnum", fmt("%d\n", devnum));
        write(dev, "bDeviceClass", fmt("%s\n", deviceClass));
        return dev;
    }

    /** An interface directory, inside its device's, which is where a scan reads it. */
    private File addInterface(File dev, int index, String cls) throws IOException {
        var iface = new File(dev, fmt("%s:1.%d", dev.getName(), index));
        assertTrue(iface.isDirectory() || iface.mkdirs());
        write(iface, "bInterfaceClass", fmt("%s\n", cls));
        return iface;
    }

    /** Points an interface's driver link at a directory named after the driver, as sysfs does. */
    private void bindDriver(File iface, String driver) throws IOException {
        var drivers = folder.getRoot().toPath().resolve("drivers");
        Files.createDirectories(drivers.resolve(driver));
        Files.createSymbolicLink(iface.toPath().resolve("driver"), drivers.resolve(driver));
    }

    /** An unplug: the kernel takes the whole directory with it, interfaces and all. */
    private static void unplug(File dev) {
        var entries = dev.listFiles();
        if (entries != null)
            for (var entry : entries) {
                if (entry.isDirectory()) unplug(entry);
                else assertTrue(entry.delete());
            }
        assertTrue(dev.delete());
    }

    private List<UsbHostDevice> scan() {
        return new UsbHostInventory(folder.getRoot().getAbsolutePath(), "/dev/bus/usb").scan();
    }

    private static List<String> names(List<UsbHostDevice> devices) {
        var names = new ArrayList<String>();
        for (var device : devices) names.add(device.sysfs);
        return names;
    }

    @Test
    public void twoReadingsOfAnUnmovedTreeAreNoDifference() throws IOException {
        addInterface(device("1-1.3", 4, "ef"), 0, CLASS_VIDEO);
        var diff = UsbHostInventory.Diff.between(scan(), scan());
        assertTrue(diff.isEmpty());
        assertEquals(1, diff.all.size());
    }

    @Test
    public void aDeviceThatArrivedIsAnAddition() throws IOException {
        var before = scan();
        addInterface(device("1-1.3", 4, "ef"), 0, CLASS_VIDEO);
        var diff = UsbHostInventory.Diff.between(before, scan());
        assertFalse(diff.isEmpty());
        assertEquals(List.of("1-1.3"), names(diff.added));
        assertTrue(diff.removed.isEmpty());
        assertTrue(diff.changed.isEmpty());
        assertEquals("plug", diff.reason());
    }

    @Test
    public void aRemovalOnlyDiffIsStillADifference() throws IOException {
        var dev = device("1-1.2.1", 57, "00");
        addInterface(dev, 0, "08");
        var before = scan();
        // The subtree of the on-device run going down with its hub: nothing was added, and the
        // pass that gives the hub its driver back is the only thing that can bring any of it
        // back. A diff that reports only removals used to raise no pass at all.
        unplug(dev);
        var diff = UsbHostInventory.Diff.between(before, scan());
        assertFalse(diff.isEmpty());
        assertEquals(List.of("1-1.2.1"), names(diff.removed));
        assertTrue(diff.added.isEmpty());
        assertTrue(diff.changed.isEmpty());
        assertEquals("unplug", diff.reason());
    }

    @Test
    public void aDeviceThatLostItsDriverIsADifference() throws IOException {
        var dev = device("1-1", 2, CLASS_HUB);
        var iface = addInterface(dev, 0, CLASS_HUB);
        bindDriver(iface, "hub");
        var before = scan();
        assertEquals(UsbHostDevice.State.HOSTUSE, before.get(0).state());
        // The hub of the on-device run, unbound by hand: binding and unbinding creates and
        // removes no device node, so this difference is the only thing that can mention it.
        assertTrue(new File(iface, "driver").delete());
        var now = scan();
        assertEquals(UsbHostDevice.State.IDLE, now.get(0).state());
        var diff = UsbHostInventory.Diff.between(before, now);
        assertFalse(diff.isEmpty());
        assertEquals(List.of("1-1"), names(diff.changed));
        assertTrue(diff.added.isEmpty());
        assertTrue(diff.removed.isEmpty());
        assertEquals("drivers", diff.reason());
    }

    @Test
    public void interfacesAppearingWithNoDriverAreADifferenceTheStateCannotSee() throws IOException {
        var dev = device("1-1", 2, CLASS_HUB);
        var before = scan();
        // A device probe has just given it a configuration: the interfaces exist and the gate
        // keeps every driver off them, so it reads idle before and idle after. The interface
        // probe that revives the subtree is still owed, and only this difference asks for it.
        addInterface(dev, 0, CLASS_HUB);
        var now = scan();
        assertEquals(UsbHostDevice.State.IDLE, before.get(0).state());
        assertEquals(UsbHostDevice.State.IDLE, now.get(0).state());
        var diff = UsbHostInventory.Diff.between(before, now);
        assertFalse(diff.isEmpty());
        assertEquals(List.of("1-1"), names(diff.changed));
        assertEquals("drivers", diff.reason());
    }

    @Test
    public void aReplugAtTheSameNameIsAnAdditionAndARemoval() throws IOException {
        addInterface(device("1-1.3", 4, "ef"), 0, CLASS_VIDEO);
        var before = scan();
        // Both halves inside one quiet period: the same sysfs name, a fresh devnum. By name
        // alone the two scans would agree that nothing had happened.
        device("1-1.3", 90, "ef");
        var diff = UsbHostInventory.Diff.between(before, scan());
        assertEquals(List.of("1-1.3"), names(diff.added));
        assertEquals(List.of("1-1.3"), names(diff.removed));
        assertTrue(diff.changed.isEmpty());
        assertEquals(90, diff.added.get(0).devnum);
        assertEquals("plug", diff.reason());
    }
}
