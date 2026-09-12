// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The crosvm usb CLI prints one line; these are the lines it prints. */
public final class CrosvmUsbControlParseTest {
    @Test
    public void attachReturnsThePortItWasGiven() throws Exception {
        assertEquals(3, CrosvmUsbControl.parseAttach("ok 3\n"));
    }

    @Test
    public void attachRefusalBecomesTheToken() {
        var e = assertThrows(UsbControlException.class,
            () -> CrosvmUsbControl.parseAttach("no_available_port"));
        assertEquals("no_available_port", e.token);
    }

    @Test
    public void detachReturnsThePort() throws Exception {
        assertEquals(2, CrosvmUsbControl.parseDetach("ok 2"));
    }

    @Test
    public void listSplitsIntoPortVidPid() throws Exception {
        var entries = CrosvmUsbControl.parseList("devices 1 090c 1000 2 0bda 8153");
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).port);
        assertEquals("090c", entries.get(0).vid);
        assertEquals("1000", entries.get(0).pid);
        assertEquals(2, entries.get(1).port);
        assertEquals("0bda", entries.get(1).vid);
        assertEquals("8153", entries.get(1).pid);
    }

    @Test
    public void anEmptyListIsJustTheKeyword() throws Exception {
        assertTrue(CrosvmUsbControl.parseList("devices").isEmpty());
    }

    @Test
    public void listRefusesAPartialGroup() {
        // Two tokens after the keyword address no device; taking the empty prefix of them would
        // report a VM with nothing attached.
        assertThrows(UsbControlException.class,
            () -> CrosvmUsbControl.parseList("devices 1 090c"));
    }

    @Test
    public void aConnectFailureIsTheVmmBeingUnreachable() {
        // What the CLI wrote, on device, when run at the RUNNING edge: the VMM was not up yet.
        assertTrue(CrosvmUsbControl.isUnreachable(
            "[ERROR vm_control::sys::linux] failed to connect to socket at "
                + "\"/data/data/cn.classfun.droidvm/run/vm/crosvm.sock\": "
                + "Connection refused (os error 111)\n"
                + "exiting with error 1: usb subcommand failed"));
        // Anything the VMM or the device answered is not that.
        assertFalse(CrosvmUsbControl.isUnreachable("exiting with error 1: usb subcommand failed"));
        assertFalse(CrosvmUsbControl.isUnreachable("no_available_port"));
        assertFalse(CrosvmUsbControl.isUnreachable(""));
    }

    @Test
    public void listRefusesAnythingElse() {
        var e = assertThrows(UsbControlException.class,
            () -> CrosvmUsbControl.parseList("garbage"));
        assertEquals("garbage", e.token);
    }

    /** What the VMM logged, on device, while refusing the attach that got no_available_port. */
    private static final String HUB_DIED =
        "[2026-09-05T12:28:12.882479153+00:00 INFO  hypervisor::gunyah::aarch64] GH: layout\n"
            + "[2026-09-05T12:28:12.898585091+00:00 INFO  devices::usb::xhci::usb_hub] "
            + "usb_hub: backend attached to port 1\n"
            + "[2026-09-05T12:28:12.898643059+00:00 ERROR devices::usb::backend::device_provider] "
            + "failed to connect device to hub: failed to attach device to port 1: "
            + "cannot add event: event ring is uninitialized\n"
            + "[2026-09-05T12:28:13.107326028+00:00 ERROR devices::usb::xhci::ring_buffer_stop_cb] "
            + "callback failed failed to detach device from port 1: "
            + "cannot add event: event ring is uninitialized\n";
    private static final String HUB_DIED_CAUSE = "failed to connect device to hub: "
        + "failed to attach device to port 1: cannot add event: event ring is uninitialized";
    /** An ERROR from another subsystem entirely, which the same log carries first. */
    private static final String GPU_ERROR_LINE =
        "[2026-09-05T12:28:12.1+00:00 ERROR rutabaga_gfx::virgl_renderer] "
            + "kgsl_ccmd_gem_set_iova:1412: Could not lookup obj: res_id=2\n";
    private static final String GPU_ERROR_CAUSE =
        "kgsl_ccmd_gem_set_iova:1412: Could not lookup obj: res_id=2";

    @Test
    public void theFirstErrorLineIsTheCauseWithoutItsPrefix() {
        assertEquals(HUB_DIED_CAUSE, CrosvmUsbControl.firstErrorLine(HUB_DIED));
    }

    @Test
    public void infoAndWarnLinesAreNotACause() {
        assertNull(CrosvmUsbControl.firstErrorLine(""));
        assertNull(CrosvmUsbControl.firstErrorLine(
            "[2026-09-05T12:28:12.899414830+00:00 INFO  crosvm] exiting with success\n"));
        assertNull(CrosvmUsbControl.firstErrorLine(
            "[2026-09-05T12:22:44.785153705+00:00 WARN  devices::usb::xhci::xhci_transfer] "
                + "xhci: endpoint is stalled. set state to Halted"));
        // The word alone, outside crosvm's log shape, is not one of its lines.
        assertNull(CrosvmUsbControl.firstErrorLine("ERROR: something else entirely"));
    }

    @Test
    public void aUsbErrorWinsOverAnEarlierUnrelatedOne() {
        var gpuFirst = fmt("%s%s", GPU_ERROR_LINE, HUB_DIED);
        assertEquals(HUB_DIED_CAUSE, CrosvmUsbControl.firstErrorLine(gpuFirst));
        // With no USB line at all, the first ERROR is still better than nothing.
        assertEquals(GPU_ERROR_CAUSE, CrosvmUsbControl.firstErrorLine(GPU_ERROR_LINE));
        // A build whose logger prints no time is read the same way.
        assertEquals("boom", CrosvmUsbControl.firstErrorLine("[ERROR devices::usb::xhci] boom"));
    }

    @Test
    public void aRefusalWithoutAnErrorLineIsJustTheToken() {
        assertEquals("crosvm usb attach failed: no_available_port",
            CrosvmUsbControl.attachFailureMessage("no_available_port",
                "[2026-09-05T12:28:12.899414830+00:00 INFO  crosvm] exiting with success", ""));
        assertEquals("crosvm usb attach failed: failed_to_open_device",
            CrosvmUsbControl.attachFailureMessage("failed_to_open_device", "", ""));
    }

    @Test
    public void aRefusalCarriesTheCauseTheVmmLogged() {
        // The on-device case: the CLI's stderr says success, the VMM's says why.
        assertEquals(fmt("crosvm usb attach failed: no_available_port (%s)", HUB_DIED_CAUSE),
            CrosvmUsbControl.attachFailureMessage("no_available_port",
                "[2026-09-05T12:28:12.899414830+00:00 INFO  crosvm] exiting with success",
                HUB_DIED));
    }

    @Test
    public void theCliOwnStderrIsReadFirst() {
        assertEquals("crosvm usb attach failed: no_such_device (cli said so)",
            CrosvmUsbControl.attachFailureMessage("no_such_device",
                "[ERROR crosvm] cli said so",
                "[ERROR devices::foo] vmm said so"));
    }
}
