// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The second event source, read over the datagrams the kernel actually sent. Every message here
 * was captured from 5568 with a netlink listener while usbhid was unbound from and rebound to
 * 1-1.6:1.0 -- the case that moves no node, raises no inotify event, and was invisible to the
 * daemon until this existed.
 */
public final class UsbUeventTest {
    /** A uevent as the kernel writes it: NUL-separated fields, the header first. */
    private static byte[] message(String... fields) {
        var out = new ByteArrayOutputStream();
        for (var field : fields) {
            out.writeBytes(field.getBytes(StandardCharsets.UTF_8));
            out.write(0);
        }
        return out.toByteArray();
    }

    private static final String IFACE_PATH =
        "/devices/platform/soc/a600000.ssusb/a600000.dwc3/xhci-hcd.1.auto/usb1/1-1/1-1.6/1-1.6:1.0";

    @Test
    public void anInterfaceBindCarriesTheDriverThatTookIt() {
        var raw = message(
            fmt("bind@%s", IFACE_PATH),
            "ACTION=bind",
            fmt("DEVPATH=%s", IFACE_PATH),
            "SUBSYSTEM=usb",
            "DEVTYPE=usb_interface",
            "DRIVER=usbhid",
            "PRODUCT=bda/1100/101",
            "INTERFACE=3/0/0",
            "MODALIAS=usb:v0BDAp1100d0101dc00dsc00dp00ic03isc00ip00in00",
            "SEQNUM=20256");
        var event = UsbUevent.parse(raw, raw.length);
        assertEquals("bind", event.action);
        assertEquals("1-1.6:1.0", event.name);
        assertEquals("usb_interface", event.devtype);
        assertEquals("usbhid", event.driver);
        assertTrue(event.isDriverChange());
    }

    /** The unbind carries no DRIVER: by the time it is sent the driver is already gone. */
    @Test
    public void anInterfaceUnbindCarriesNoDriver() {
        var raw = message(
            fmt("unbind@%s", IFACE_PATH),
            "ACTION=unbind",
            fmt("DEVPATH=%s", IFACE_PATH),
            "SUBSYSTEM=usb",
            "DEVTYPE=usb_interface",
            "PRODUCT=bda/1100/101",
            "INTERFACE=3/0/0",
            "SEQNUM=20251");
        var event = UsbUevent.parse(raw, raw.length);
        assertEquals("unbind", event.action);
        assertEquals("1-1.6:1.0", event.name);
        assertEquals("", event.driver);
        assertTrue(event.isDriverChange());
    }

    /**
     * A root hub registering, which is what a dual-role port coming back to host mode sends. Its
     * name is the one sysfs lists it under, so the pass that follows can speak about it.
     */
    @Test
    public void aRootHubAddIsAUsbDeviceNamedAfterItsBus() {
        var path = "/devices/platform/soc/a600000.ssusb/a600000.dwc3/xhci-hcd.1.auto/usb1";
        var raw = message(
            fmt("add@%s", path),
            "ACTION=add",
            fmt("DEVPATH=%s", path),
            "SUBSYSTEM=usb",
            "DEVTYPE=usb_device",
            "PRODUCT=1d6b/2/606",
            "DEVNAME=bus/usb/001/001",
            "SEQNUM=20260");
        var event = UsbUevent.parse(raw, raw.length);
        assertEquals("add", event.action);
        assertEquals("usb1", event.name);
        assertEquals("usb_device", event.devtype);
        assertFalse(event.isDriverChange());
    }

    /**
     * Everything else is dropped before anything is done with it. The same unbind sends one of
     * these for the character device the driver had published, and it names a path that is not a
     * device this daemon knows.
     */
    @Test
    public void aMessageFromAnotherSubsystemIsNotRead() {
        var path = fmt("%s/usbmisc/hiddev0", IFACE_PATH);
        var raw = message(
            fmt("remove@%s", path),
            "ACTION=remove",
            fmt("DEVPATH=%s", path),
            "SUBSYSTEM=usbmisc",
            "MAJOR=180",
            "MINOR=96",
            "DEVNAME=usb/hiddev0",
            "SEQNUM=20247");
        assertNull(UsbUevent.parse(raw, raw.length));
    }

    @Test
    public void aMessageWithoutTheKeysItNeedsIsNotRead() {
        var raw = message(fmt("bind@%s", IFACE_PATH), "SUBSYSTEM=usb", "DEVTYPE=usb_interface");
        assertNull(UsbUevent.parse(raw, raw.length));
        var empty = new byte[0];
        assertNull(UsbUevent.parse(empty, 0));
    }

    /** Read to the length given, not to the end of the buffer the reader happens to reuse. */
    @Test
    public void onlyTheBytesThisDatagramBroughtAreRead() {
        var raw = message(
            "ACTION=bind", fmt("DEVPATH=%s", IFACE_PATH), "SUBSYSTEM=usb", "DRIVER=usbhid");
        var buffer = new byte[8192];
        System.arraycopy(raw, 0, buffer, 0, raw.length);
        // What a previous, longer message left behind, which a reader that trusted the buffer
        // rather than the count would read as part of this one.
        var stale = message("ACTION=unbind", "SUBSYSTEM=usbmisc");
        System.arraycopy(stale, 0, buffer, raw.length, stale.length);
        var event = UsbUevent.parse(buffer, raw.length);
        assertEquals("bind", event.action);
        assertEquals("usbhid", event.driver);
    }
}
