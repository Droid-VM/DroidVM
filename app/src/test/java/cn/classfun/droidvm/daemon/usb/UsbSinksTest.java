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
import cn.classfun.droidvm.daemon.usb.UsbSinks.Owed;
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
    public void aRecordSaysWhoHidADeviceAndNeverWhetherItIsHidden() {
        // The bug this pins. Sink the device by rule, delete the rule -- the reconcile writes
        // authorized=1 and the record goes with it -- then set the same rule again. The device
        // is back on the host with its drivers bound, and only the host says so: writing
        // authorized creates and removes no /dev/bus/usb node, so the inventory's watch never
        // fires and its snapshot still reads deauthorized. A pass that asked the snapshot and
        // the missing record got both halves wrong at once, hid nothing, and said nothing.
        var sinks = new UsbSinks();
        sinks.put(STICK, sinkDecision(), false, 7);
        assertEquals(Reconcile.RESTORE, sinks.reconcile(STICK, false, Pin.NONE, false));
        sinks.remove(STICK);
        assertEquals(Owed.HIDE, sinks.owedBySink(STICK, 7, true));

        // And the other way round: a record left over from an instance that is gone, or from a
        // write that never landed, is no reason to leave an authorized device on the host.
        sinks.put(STICK, sinkDecision(), false, 7);
        assertEquals(Owed.HIDE, sinks.owedBySink(STICK, 7, true));
    }

    @Test
    public void theSinkIsANoOpOnlyWhereTheHostSaysTheDeviceIsHidden() {
        // The same rule one level below the pass, where the sink itself decides whether it has
        // anything to write. Everything that hides a device comes through there -- the rules
        // pass, the fast lane, the management page's own Sink -- and a record answering
        // "already hidden" on its own is how a request could report success over a device
        // sitting authorized on the host with its drivers bound. Deauthorizing and authorizing
        // again is no unplug, so the devnum does not change and the record still names this
        // very instance; it is still saying nothing about whether the device is hidden.
        var sinks = new UsbSinks();
        sinks.put(STICK, sinkDecision(), false, 7);
        assertEquals(Owed.DONE, sinks.owedBySink(STICK, 7, false));
        // echo 1 > /sys/bus/usb/devices/1-1.2.2/authorized, or a write that never landed:
        assertEquals(Owed.HIDE, sinks.owedBySink(STICK, 7, true));
    }

    @Test
    public void aRecordAboutAnotherInstanceDecidesNothingAboutThisOne() {
        // A different unit in the socket, hidden: the record is the provenance of the one that
        // left. It neither makes this one ours -- there is nothing of this device's to give
        // back under it -- nor leaves it to the reconcile as a stranger's, because the run that
        // has a rule for it claims it, with a record minted for the instance actually there.
        var sinks = new UsbSinks();
        sinks.put(STICK, sinkDecision(), false, 7);
        assertEquals(Owed.HIDE, sinks.owedBySink(STICK, 9, false));
        assertEquals(Owed.HIDE, sinks.owedBySink(STICK, 9, true));
    }

    @Test
    public void aDeviceHiddenByNobodyHereIsLeftToTheReconcile() {
        // Daemon start, or somebody's shell: hidden, with no record to say this run did it. The
        // pass writes nothing and the reconcile adopts it, so that nothing is announced about
        // something that happened before the daemon was there to announce it.
        var sinks = new UsbSinks();
        assertEquals(Owed.ADOPT, sinks.owedBySink(STICK, 7, false));
        assertEquals(Reconcile.ADOPT, sinks.reconcile(STICK, false, Pin.NONE, true));
        sinks.put(STICK, sinkDecision(), false, 7);
        // Ours now: hiding it again is the sink's own no-op, and it is adopted no second time.
        assertEquals(Owed.DONE, sinks.owedBySink(STICK, 7, false));
        assertEquals(Reconcile.LEAVE, sinks.reconcile(STICK, false, Pin.NONE, true));
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
    public void withTheSwitchOffTheReconcileStillGivesEveryHiddenDeviceBack() {
        // The pass gates its plan on the master switch and deliberately not this: the rules
        // answer nothing while it is off, which reads here as "no rule hides this device, give
        // it back" -- and that is the only thing that ever authorizes one again. Gated too, a
        // device the falling edge could not reach, or one hidden by hand whose record died with
        // the daemon, would stay invisible to Android and to every VM until it was unplugged.
        var map = new EnumMap<Layer, List<Rule>>(Layer.class);
        map.put(Layer.DEVICE, List.of(new Rule("090c:1000", null, null, null, Target.SINK)));
        var engine = new UsbRuleEngine();
        engine.setRules(UsbRules.build(false, map, null));
        var rulesSink = engine.decide(new Device(STICK, "090c:1000", "1.2.2"),
            vm -> VMState.STOPPED, (vm, controller) -> true) != null;
        assertFalse(rulesSink);

        var sinks = new UsbSinks();
        assertEquals(Reconcile.RESTORE, sinks.reconcile(STICK, false, Pin.NONE, rulesSink));
        // Except what the user hid by hand: the management page is a direct action and goes on
        // working while the switch is off, so its record and its pin outrank the reconcile.
        sinks.put(STICK, null, true, 7);
        assertEquals(Reconcile.LEAVE, sinks.reconcile(STICK, false, Pin.SINK, rulesSink));
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
