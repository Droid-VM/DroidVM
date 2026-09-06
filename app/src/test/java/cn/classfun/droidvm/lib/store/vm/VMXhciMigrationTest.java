// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * The fold of the VM-level "usb" boolean into an xHCI controller entry, and the id minting that
 * makes a USB rule able to point at one.
 *
 * <p>The migration is called the way {@code VMConfig(JSONObject)} calls it, on a hand-built
 * {@link DataItem}: org.json is a stub under the test android.jar (see UsbRulesTest), so the
 * config is built directly rather than parsed.</p>
 */
public final class VMXhciMigrationTest {
    /** A config as an older build wrote it: the boolean, and no peripheral for it. */
    private static DataItem legacy(boolean usb) {
        var item = DataItem.newObject();
        item.set(VMXhciConfig.KEY_USB, usb);
        return item;
    }

    private static DataItem controllerEntry(String id, long usb2, long usb3) {
        var entry = DataItem.newObject();
        entry.set("type", PeripheralType.XHCI_USB);
        if (id != null) entry.set(VMXhciConfig.KEY_ID, id);
        entry.set(VMXhciConfig.KEY_USB2, usb2);
        entry.set(VMXhciConfig.KEY_USB3, usb3);
        return entry;
    }

    private static DataItem withPeripherals(DataItem... entries) {
        var item = DataItem.newObject();
        var arr = DataItem.newArray();
        for (var entry : entries) arr.append(entry);
        item.set(VMXhciConfig.KEY_PERIPHERALS, arr);
        return item;
    }

    @Test
    public void aConfigWithUsbTrueGainsOneControllerWithEightPortsEachWay() {
        var item = legacy(true);
        VMXhciConfig.migrate(item);
        var controllers = VMXhciConfig.listControllers(item);
        assertEquals(1, controllers.size());
        assertEquals("xhci-0", controllers.get(0).getControllerId());
        // crosvm's real geometry, so a converted VM opens with no warning on the default backend.
        assertEquals(8, controllers.get(0).getUsb2Ports());
        assertEquals(8, controllers.get(0).getUsb3Ports());
        assertEquals(PeripheralType.XHCI_USB, controllers.get(0).getType());
        assertTrue(VMXhciConfig.isEnabled(item));
        assertEquals("xhci-0", VMXhciConfig.firstControllerId(item));
    }

    @Test
    public void aConfigWithUsbFalseGainsNoController() {
        var item = legacy(false);
        VMXhciConfig.migrate(item);
        assertTrue(VMXhciConfig.listControllers(item).isEmpty());
        assertFalse(VMXhciConfig.isEnabled(item));
        assertNull(VMXhciConfig.firstControllerId(item));
        assertNull(VMXhciConfig.findController(item, null));
    }

    @Test
    public void aConfigWithNoUsbKeyGainsNoController() {
        // An absent key reads as off, which is what crosvm and the passthrough manager always
        // made of it. The one shape that meant "on" -- a config with no peripherals array at all
        // -- is QEMU's to keep honouring, and it does, from the boolean.
        var item = DataItem.newObject();
        VMXhciConfig.migrate(item);
        assertTrue(VMXhciConfig.listControllers(item).isEmpty());
        assertFalse(item.optBoolean(VMXhciConfig.KEY_USB, true));
    }

    @Test
    public void migrationIsIdempotent() {
        var item = legacy(true);
        VMXhciConfig.migrate(item);
        VMXhciConfig.migrate(item);
        VMXhciConfig.migrate(item);
        var controllers = VMXhciConfig.listControllers(item);
        assertEquals(1, controllers.size());
        assertEquals("xhci-0", controllers.get(0).getControllerId());
    }

    @Test
    public void bothProcessesMintTheSameId() {
        // vm_create/vm_modify build a VMConfig from the config the app pushed, so this migration
        // also runs inside the daemon, on a copy it never writes back. The two runs have to agree
        // or every rule keyed on the id would point at nothing.
        var app = legacy(true);
        var daemon = legacy(true);
        VMXhciConfig.migrate(app);
        VMXhciConfig.migrate(daemon);
        assertEquals(VMXhciConfig.firstControllerId(app), VMXhciConfig.firstControllerId(daemon));

        // And the daemon's re-run over what the app saved leaves that id alone.
        var pushed = new DataItem(app);
        VMXhciConfig.migrate(pushed);
        assertEquals(1, VMXhciConfig.listControllers(pushed).size());
        assertEquals("xhci-0", VMXhciConfig.firstControllerId(pushed));
    }

    @Test
    public void anExistingControllerIsLeftAlone() {
        var item = withPeripherals(controllerEntry("xhci-1", 4, 2));
        item.set(VMXhciConfig.KEY_NEXT, 2L);
        VMXhciConfig.migrate(item);
        var controllers = VMXhciConfig.listControllers(item);
        assertEquals(1, controllers.size());
        assertEquals("xhci-1", controllers.get(0).getControllerId());
        assertEquals(4, controllers.get(0).getUsb2Ports());
        assertEquals(2, controllers.get(0).getUsb3Ports());
        // A rule that names no controller means this one, first in the array.
        assertEquals("xhci-1", VMXhciConfig.firstControllerId(item));
        assertNotNull(VMXhciConfig.findController(item, "xhci-1"));
        assertNull(VMXhciConfig.findController(item, "xhci-0"));
    }

    @Test
    public void anEntryWithNoIdGetsOneWithoutTakingAnothersName() {
        // A config that carries ids but no counter -- hand-written, or written by a build that
        // lost the key -- must not hand out an id it is already using.
        var item = withPeripherals(controllerEntry("xhci-0", 8, 8), controllerEntry(null, 8, 8));
        VMXhciConfig.migrate(item);
        var controllers = VMXhciConfig.listControllers(item);
        assertEquals(2, controllers.size());
        assertEquals("xhci-0", controllers.get(0).getControllerId());
        assertEquals("xhci-1", controllers.get(1).getControllerId());
        assertEquals(2, item.optLong(VMXhciConfig.KEY_NEXT, -1));
    }

    @Test
    public void idsAreNeverReused() {
        // Deleting a controller and adding another must not re-adopt the rules that pointed at
        // the deleted one, so the counter only ever goes up.
        var item = legacy(true);
        VMXhciConfig.migrate(item);
        assertEquals("xhci-0", VMXhciConfig.firstControllerId(item));
        item.opt(VMXhciConfig.KEY_PERIPHERALS, DataItem.newArray()).remove(0);
        assertTrue(VMXhciConfig.listControllers(item).isEmpty());
        assertEquals("xhci-1", VMXhciConfig.addController(item).getControllerId());
        // Even a fold that runs after the deletion cannot mint xhci-0 again.
        var second = VMXhciConfig.addController(item);
        assertEquals("xhci-2", second.getControllerId());
    }

    @Test
    public void aControllerTheUserRemovedIsNotMintedAgain() {
        // The counter says the fold has run, so an empty list means the user emptied it -- not a
        // config that predates controllers. Minting another would hand it a new id and dangle
        // every rule that named the one that was removed.
        var item = legacy(true);
        VMXhciConfig.migrate(item);
        item.opt(VMXhciConfig.KEY_PERIPHERALS, DataItem.newArray()).remove(0);
        VMXhciConfig.migrate(item);
        assertTrue(VMXhciConfig.listControllers(item).isEmpty());
        assertFalse(item.optBoolean(VMXhciConfig.KEY_USB, true));
    }

    @Test
    public void theUsbMirrorFollowsTheControllerList() {
        // Nothing new reads the boolean, but a build that does not know the peripheral type reads
        // nothing else -- so it has to keep saying what the list says.
        var converted = legacy(true);
        VMXhciConfig.migrate(converted);
        assertTrue(converted.optBoolean(VMXhciConfig.KEY_USB, false));

        var off = legacy(false);
        VMXhciConfig.migrate(off);
        assertFalse(off.optBoolean(VMXhciConfig.KEY_USB, true));

        // A config whose controller was added without the boolean being touched still agrees.
        var handWritten = withPeripherals(controllerEntry("xhci-0", 8, 8));
        handWritten.set(VMXhciConfig.KEY_USB, false);
        VMXhciConfig.migrate(handWritten);
        assertTrue(handWritten.optBoolean(VMXhciConfig.KEY_USB, false));
    }

    @Test
    public void portCountsAreHeldToWhatTheEditorCanExpress() {
        var item = withPeripherals(controllerEntry("xhci-0", 99, -3));
        VMXhciConfig.migrate(item);
        var controller = VMXhciConfig.listControllers(item).get(0);
        assertEquals(VMXhciConfig.MAX_PORTS, controller.getUsb2Ports());
        assertEquals(VMXhciConfig.MIN_PORTS, controller.getUsb3Ports());
        controller.setUsb2Ports(42);
        controller.setUsb3Ports(-1);
        assertEquals(VMXhciConfig.MAX_PORTS, controller.getUsb2Ports());
        assertEquals(VMXhciConfig.MIN_PORTS, controller.getUsb3Ports());
        // An entry that says nothing about its ports has crosvm's geometry.
        var bare = DataItem.newObject();
        bare.set("type", PeripheralType.XHCI_USB);
        var defaults = new VMPeripheralConfig(bare);
        assertEquals(VMXhciConfig.DEFAULT_PORTS, defaults.getUsb2Ports());
        assertEquals(VMXhciConfig.DEFAULT_PORTS, defaults.getUsb3Ports());
    }

    @Test
    public void aSoundCardIsNotAController() {
        // listControllers reads the type, not the position: a card with the same shape of keys is
        // still a sound card, and a VM that has only one has no USB.
        var item = withPeripherals(VMPeripheralConfig.createDefaultVirtioSound().item);
        item.set(VMXhciConfig.KEY_USB, false);
        VMXhciConfig.migrate(item);
        assertTrue(VMXhciConfig.listControllers(item).isEmpty());
        assertFalse(VMXhciConfig.isEnabled(item));
    }
}
