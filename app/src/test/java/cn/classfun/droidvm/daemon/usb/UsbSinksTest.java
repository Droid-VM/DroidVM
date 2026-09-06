// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumMap;
import java.util.List;

import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Decision;
import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Device;
import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Pin;
import cn.classfun.droidvm.daemon.usb.UsbRules.Layer;
import cn.classfun.droidvm.daemon.usb.UsbRules.Rule;
import cn.classfun.droidvm.daemon.usb.UsbRules.Target;
import cn.classfun.droidvm.daemon.usb.UsbSinks.Reconcile;
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * What is owed to a device the host shows deauthorized. The manager's part -- the scan, the
 * sysfs write, the lock -- is not exercised here; this is the decision alone, which is the part
 * a daemon start gets exactly one chance at.
 */
public final class UsbSinksTest {
    private static final String STICK = "1-1.2.2";

    /** The decision a sink rule for the stick would produce, for the record's provenance. */
    private static Decision sinkDecision() {
        var map = new EnumMap<Layer, List<Rule>>(Layer.class);
        map.put(Layer.DEVICE, List.of(new Rule("090c:1000", null, null, null, Target.SINK)));
        var engine = new UsbRuleEngine();
        engine.setRules(UsbRules.build(map, null));
        var decision = engine.decide(new Device(STICK, "090c:1000", "1.2.2"),
            vm -> VMState.STOPPED, (vm, controller) -> true);
        assertNotNull(decision);
        return decision;
    }

    @Test
    public void aRecordKeepsWhoAskedAndWhichInstance() {
        var sinks = new UsbSinks();
        sinks.put(STICK, sinkDecision(), false, 7);
        var record = sinks.get(STICK);
        assertNotNull(record);
        assertEquals(Layer.DEVICE, record.layer);
        assertEquals(0, record.index);
        assertFalse(record.manual);
        assertEquals(7, record.devnum);
        assertTrue(sinks.has(STICK));

        // The user's own is the one no rule pass may undo, and it names no rule.
        sinks.put(STICK, null, true, 7);
        record = sinks.get(STICK);
        assertNotNull(record);
        assertNull(record.layer);
        assertEquals(-1, record.index);
        assertTrue(record.manual);

        sinks.remove(STICK);
        assertFalse(sinks.has(STICK));
        assertNull(sinks.get(STICK));
    }

    @Test
    public void atDaemonStartTheRulesDecideEveryDeauthorizedDevice() {
        // The map is empty, because nothing about the sink is persisted: what the previous run
        // left behind is read off the host and answered by the rules alone.
        var sinks = new UsbSinks();
        assertEquals(Reconcile.ADOPT, sinks.reconcile(STICK, false, Pin.NONE, true));
        assertEquals(Reconcile.RESTORE, sinks.reconcile(STICK, false, Pin.NONE, false));
    }

    @Test
    public void anAdoptedDeviceIsNotAdoptedTwice() {
        var sinks = new UsbSinks();
        assertEquals(Reconcile.ADOPT, sinks.reconcile(STICK, false, Pin.NONE, true));
        sinks.put(STICK, sinkDecision(), false, 7);
        assertEquals(Reconcile.LEAVE, sinks.reconcile(STICK, false, Pin.NONE, true));
        // And when the rule that hid it goes, so does the device's invisibility.
        assertEquals(Reconcile.RESTORE, sinks.reconcile(STICK, false, Pin.NONE, false));
    }

    @Test
    public void theManagementPagesOwnSinkSurvivesEveryPass() {
        // Without this the next usb_rules_set would authorize it straight back, and the page's
        // direct action would last exactly until the user saved a rule.
        var sinks = new UsbSinks();
        sinks.put(STICK, null, true, 7);
        assertEquals(Reconcile.LEAVE, sinks.reconcile(STICK, false, Pin.NONE, false));
        assertEquals(Reconcile.LEAVE, sinks.reconcile(STICK, false, Pin.SINK, false));
    }

    @Test
    public void aPinIsTheUsersWordAndOutranksTheRulesBothWays() {
        var sinks = new UsbSinks();
        // Hidden by hand: the rules no longer sinking it changes nothing until it is unplugged.
        assertEquals(Reconcile.LEAVE, sinks.reconcile(STICK, false, Pin.SINK, false));
        // Asked for on the host: given back even where a rule would hide it.
        assertEquals(Reconcile.RESTORE, sinks.reconcile(STICK, false, Pin.HOST, true));
    }

    @Test
    public void anAttachedDeviceIsNeverTouched() {
        // Deauthorizing takes every interface away, so a VM cannot be holding one; if it ever
        // happens the manager says so out loud and nothing here writes to the device.
        var sinks = new UsbSinks();
        assertEquals(Reconcile.LEAVE, sinks.reconcile(STICK, true, Pin.NONE, false));
        assertEquals(Reconcile.LEAVE, sinks.reconcile(STICK, true, Pin.NONE, true));
    }

    @Test
    public void theSwitchGoingOffLeavesNoRecordBehind() {
        // Everything hidden is authorized again on that edge, so a record would be provenance
        // for a device nothing is hiding any more -- and the next pass reads the host's own
        // authorized flag rather than this map anyway.
        var sinks = new UsbSinks();
        sinks.put(STICK, sinkDecision(), false, 7);
        sinks.put("1-1.6", null, true, 3);
        sinks.clear();
        assertFalse(sinks.has(STICK));
        assertFalse(sinks.has("1-1.6"));
        assertNull(sinks.get(STICK));
        // A device the write could not reach is decided by the rules again, from the scan.
        assertEquals(Reconcile.RESTORE, sinks.reconcile(STICK, false, Pin.NONE, false));
    }
}
