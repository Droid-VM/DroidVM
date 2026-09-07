// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

import cn.classfun.droidvm.daemon.usb.UsbHostDevice.State;
import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Decision;
import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Device;
import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Save;
import cn.classfun.droidvm.daemon.usb.UsbRules.Layer;
import cn.classfun.droidvm.daemon.usb.UsbRules.Rule;
import cn.classfun.droidvm.daemon.usb.UsbRules.Target;
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * The matching, over hand-built devices and a table of VM states: no sysfs, no crosvm. The
 * manager's part -- taking the lock, scanning, probing, attaching, broadcasting -- is not
 * exercised here; what a device's state is comes in on the {@link Device}, the way it comes off
 * a scan in the daemon.
 */
public final class UsbRuleEngineTest {
    private static final String VM_A = "0f4c9e2a-0000-4000-8000-00000000000a";
    private static final String VM_B = "0f4c9e2a-0000-4000-8000-00000000000b";
    private static final String VM_C = "0f4c9e2a-0000-4000-8000-00000000000c";
    /** The instance number a device carries unless a case is about replugging it. */
    private static final int DEVNUM = 7;
    /** A serialised flash drive on the hub's second downstream port, second hop. */
    private static final Device STICK = device("1-1.2.2", "090c:1000:0123456789ABCDEF");
    /** A serial-less mouse on port 1.6. */
    private static final Device MOUSE = device("1-1.6", "046d:c077");

    /** A device the gate has just handed over: enumerated, and nothing bound to it. */
    private static Device device(String sysfs, String id) {
        return device(sysfs, id, DEVNUM, State.IDLE);
    }

    private static Device device(String sysfs, String id, int devnum, State state) {
        return new Device(sysfs, id, UsbHostDevice.derivePort(sysfs), devnum, state);
    }

    /** The same unit, doing something else. */
    private static Device as(Device device, State state) {
        return device(device.sysfs, device.id, device.devnum, state);
    }

    /** The same address with another unit in it: what the kernel makes of a replug. */
    private static Device replugged(Device device) {
        return device(device.sysfs, device.id, device.devnum + 1, device.state);
    }

    private static Rule rule(String id, String port, String vm) {
        return new Rule(id, port, vm, null);
    }

    /** A rule that names one controller inside its target VM. */
    private static Rule rule(String id, String port, String vm, String controller) {
        return new Rule(id, port, vm, controller);
    }

    /** A rule that leaves whatever it matches to nobody. */
    private static Rule sink(String id, String port) {
        return new Rule(id, port, null, null, Target.SINK);
    }

    /** Every VM has every controller: what these cases vary is the state table. */
    private static BiPredicate<String, String> anyController() {
        return (vm, controller) -> true;
    }

    /**
     * The controllers each VM has. A rule that names none asks whether the VM has any, which is
     * what "the VM's first controller" comes down to.
     */
    private static BiPredicate<String, String> controllers(Map<String, List<String>> byVm) {
        return (vm, controller) -> {
            var have = byVm.get(vm);
            if (have == null || have.isEmpty()) return false;
            return controller == null || have.contains(controller);
        };
    }

    /** Only the layers named are populated; every VM id is taken on trust. */
    private static UsbRules rules(Object... layerAndList) {
        return UsbRules.build(layersOf(layerAndList), null);
    }

    /** The same rules with the master switch off: the one difference the gate reads. */
    private static UsbRules disabled(Object... layerAndList) {
        return UsbRules.build(false, layersOf(layerAndList), null);
    }

    private static Map<Layer, List<Rule>> layersOf(Object... layerAndList) {
        var map = new EnumMap<Layer, List<Rule>>(Layer.class);
        for (var i = 0; i < layerAndList.length; i += 2) {
            @SuppressWarnings("unchecked")
            var list = (List<Rule>) layerAndList[i + 1];
            map.put((Layer) layerAndList[i], list);
        }
        return map;
    }

    private static UsbRuleEngine engine(UsbRules rules) {
        var engine = new UsbRuleEngine();
        engine.setRules(rules);
        return engine;
    }

    /** A VM store: the ids named are in the states given, any other id is no VM at all. */
    private static Function<String, VMState> states(Object... idAndState) {
        var map = new HashMap<String, VMState>();
        for (var i = 0; i < idAndState.length; i += 2)
            map.put((String) idAndState[i], (VMState) idAndState[i + 1]);
        return map::get;
    }

    private static Function<String, VMState> allRunning() {
        return states(VM_A, VMState.RUNNING, VM_B, VMState.RUNNING, VM_C, VMState.RUNNING);
    }

    private static void assertDecision(Decision decision, Device device, Layer layer, int index,
                                       String vm) {
        assertNotNull(decision);
        assertEquals(device.sysfs, decision.device.sysfs);
        assertEquals(layer, decision.layer);
        assertEquals(index, decision.index);
        assertEquals(vm, decision.vm);
        assertEquals(vm == null ? Target.HOST : Target.VM, decision.target);
    }

    private static void assertDecision(Decision decision, Device device, Layer layer, int index,
                                       String vm, String controller) {
        assertDecision(decision, device, layer, index, vm);
        assertEquals(controller, decision.controller);
    }

    private static void assertSink(Decision decision, Device device, Layer layer, int index) {
        assertNotNull(decision);
        assertEquals(device.sysfs, decision.device.sysfs);
        assertEquals(layer, decision.layer);
        assertEquals(index, decision.index);
        assertEquals(Target.SINK, decision.target);
        // Nothing to attach to, and nothing for a controller to be inside of.
        assertNull(decision.vm);
        assertNull(decision.controller);
    }

    /** The fifth zone's own rule: host, and in no layer of the file at any index. */
    private static void assertDefaultHost(Decision decision, Device device) {
        assertNotNull(decision);
        assertEquals(device.sysfs, decision.device.sysfs);
        assertEquals(Target.HOST, decision.target);
        assertNull(decision.layer);
        assertEquals(-1, decision.index);
        assertNull(decision.vm);
        assertNull(decision.controller);
        // What a log line calls it, which may not read as a row the user could go and edit.
        assertEquals("the default rule", decision.where());
    }

    @Test
    public void withNoRulesAtAllTheFifthZoneStillGivesEveryDeviceToTheHost() {
        // The keyboard case: with the gate shut a device nothing matched would sit driverless,
        // so "no rule matched" has to mean the host keeps it rather than nobody does.
        var engine = engine(UsbRules.empty());
        assertDefaultHost(engine.decide(STICK, allRunning(), anyController()), STICK);
        var plan = engine.plan(List.of(STICK, MOUSE), allRunning(), anyController());
        assertEquals(2, plan.size());
        assertDefaultHost(plan.get(0), STICK);
        assertDefaultHost(plan.get(1), MOUSE);
    }

    @Test
    public void theFifthZoneRunsAfterTheFourTheFileCarries() {
        // Four layers full of rules about other devices, and then the zone the file cannot hold.
        var engine = engine(rules(
            Layer.EXACT, List.of(rule(MOUSE.id, MOUSE.port, VM_A)),
            Layer.PORT, List.of(rule(null, "9.9", VM_A)),
            Layer.DEVICE, List.of(rule("ffff:ffff", null, VM_A)),
            Layer.ANY, List.of()
        ));
        assertDefaultHost(engine.decide(STICK, allRunning(), anyController()), STICK);
        // And any row that does match outranks it, wherever it sits -- including the last layer,
        // which is the one that looks like it should already be the end of the search.
        engine.setRules(rules(Layer.ANY, List.of(sink(null, null))));
        assertSink(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.ANY, 0);
    }

    @Test
    public void layersAreConsultedInOrder() {
        var engine = engine(rules(
            Layer.EXACT, List.of(rule(STICK.id, STICK.port, VM_A)),
            Layer.PORT, List.of(rule(null, STICK.port, VM_B)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_C)),
            Layer.ANY, List.of(rule(null, null, VM_C))
        ));
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.EXACT, 0, VM_A);

        engine.setRules(rules(
            Layer.PORT, List.of(rule(null, STICK.port, VM_B)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_C))
        ));
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.PORT, 0, VM_B);

        engine.setRules(rules(Layer.DEVICE, List.of(rule(STICK.id, null, VM_C))));
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.DEVICE, 0, VM_C);
    }

    @Test
    public void listOrderDecidesWithinALayer() {
        var engine = engine(rules(Layer.PORT, List.of(
            rule(null, STICK.port, VM_B),
            rule(null, STICK.port, VM_A)
        )));
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.PORT, 0, VM_B);
    }

    @Test
    public void aRuleIsOnlyMatchedByItsOwnDevice() {
        var engine = engine(rules(
            Layer.EXACT, List.of(rule(STICK.id, "1.9", VM_A)),
            Layer.PORT, List.of(rule(null, "1.9", VM_A)),
            Layer.DEVICE, List.of(rule("ffff:ffff", null, VM_A))
        ));
        assertDefaultHost(engine.decide(STICK, allRunning(), anyController()), STICK);
    }

    @Test
    public void aVmThatIsNotRunningIsPassedOver() {
        var engine = engine(rules(Layer.PORT, List.of(
            rule(null, STICK.port, VM_A),
            rule(null, STICK.port, VM_B)
        )));
        var states = states(VM_A, VMState.STOPPED, VM_B, VMState.RUNNING);
        assertDecision(engine.decide(STICK, states, anyController()), STICK, Layer.PORT, 1, VM_B);
        // STARTING is not RUNNING either: the control socket is not there to attach through.
        states = states(VM_A, VMState.STARTING, VM_B, VMState.RUNNING);
        assertDecision(engine.decide(STICK, states, anyController()), STICK, Layer.PORT, 1, VM_B);
        // A VM the store does not know cannot be running.
        states = states(VM_B, VMState.RUNNING);
        assertDecision(engine.decide(STICK, states, anyController()), STICK, Layer.PORT, 1, VM_B);
        // And with nobody up, the fifth zone has it: the device belongs to the host until one of
        // those VMs comes up and the trigger of that edge decides it again.
        assertDefaultHost(engine.decide(STICK, states(VM_A, VMState.STOPPED, VM_B,
            VMState.STOPPED), anyController()), STICK);
    }

    @Test
    public void aLowerLayerTakesTheDeviceOfAPortRuleWhoseVmIsOff() {
        var engine = engine(rules(
            Layer.PORT, List.of(rule(null, STICK.port, VM_A)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_B))
        ));
        var states = states(VM_A, VMState.STOPPED, VM_B, VMState.RUNNING);
        assertDecision(engine.decide(STICK, states, anyController()), STICK, Layer.DEVICE, 0, VM_B);
        // Once A is back, the port rule outranks the device rule again.
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.PORT, 0, VM_A);
    }

    @Test
    public void aNullVmLeavesTheDeviceWithTheHostAndStopsTheSearch() {
        var engine = engine(rules(
            Layer.PORT, List.of(rule(null, MOUSE.port, null)),
            Layer.DEVICE, List.of(rule(MOUSE.id, null, VM_A)),
            Layer.ANY, List.of(rule(null, null, VM_A))
        ));
        assertDecision(engine.decide(MOUSE, allRunning(), anyController()), MOUSE, Layer.PORT, 0, null);
        // The host rule shows up in a plan, so a dry run can report it, with no VM.
        var plan = engine.plan(List.of(MOUSE, STICK), allRunning(), anyController());
        assertEquals(2, plan.size());
        assertDecision(plan.get(0), MOUSE, Layer.PORT, 0, null);
        assertDecision(plan.get(1), STICK, Layer.ANY, 0, VM_A);
    }

    @Test
    public void anyGoesToTheFirstRunningVmInTheList() {
        var engine = engine(rules(Layer.ANY, List.of(
            rule(null, null, VM_A),
            rule(null, null, VM_B),
            rule(null, null, VM_C)
        )));
        var states = states(VM_A, VMState.STOPPED, VM_B, VMState.RUNNING, VM_C, VMState.RUNNING);
        assertDecision(engine.decide(STICK, states, anyController()), STICK, Layer.ANY, 1, VM_B);
        assertDecision(engine.decide(MOUSE, states, anyController()), MOUSE, Layer.ANY, 1, VM_B);
    }

    // The candidate filter: the device's own state, and the user's lock. Nothing else -- and in
    // particular nothing the daemon remembers about who has what.

    @Test
    public void aDeviceInUseByAVmIsNeverACandidate() {
        // What the attachment record used to say, said by the device itself: a usbfs claim is a
        // live fd somebody owns, so no pass may decide anything about it.
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        var lent = as(STICK, State.VMUSE);
        assertFalse(engine.isCandidate(lent));
        assertTrue(engine.plan(List.of(lent), allRunning(), anyController()).isEmpty());
        // A dry run still says where the rules would put it.
        assertDecision(engine.decide(lent, allRunning(), anyController()), lent, Layer.ANY, 0,
            VM_A);
    }

    @Test
    public void bothHostuseAndIdleAreCandidates() {
        // Idle is the device the gate just handed over; hostuse is one Android already has, and
        // a rule that wants it for a VM has to be able to take it away.
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        var idle = as(STICK, State.IDLE);
        var host = as(MOUSE, State.HOSTUSE);
        assertTrue(engine.isCandidate(idle));
        assertTrue(engine.isCandidate(host));
        assertEquals(2, engine.plan(List.of(idle, host), allRunning(), anyController()).size());
    }

    @Test
    public void aLockedDeviceIsNotACandidate() {
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        engine.lock(STICK.sysfs, STICK.devnum);
        var plan = engine.plan(List.of(STICK, MOUSE), allRunning(), anyController());
        assertEquals(1, plan.size());
        assertDecision(plan.get(0), MOUSE, Layer.ANY, 0, VM_A);
        // A dry run still says what the rules would do with it.
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.ANY, 0, VM_A);
        // A new rule set does not lift the lock.
        engine.setRules(rules(Layer.DEVICE, List.of(rule(STICK.id, null, VM_A))));
        assertTrue(engine.plan(List.of(STICK), allRunning(), anyController()).isEmpty());
        // The user handing it back to the rules does.
        engine.unlock(STICK.sysfs);
        assertEquals(1, engine.plan(List.of(STICK), allRunning(), anyController()).size());
    }

    @Test
    public void aLockIsAboutOneInstanceSoAReplugClearsIt() {
        // The lock is the user's answer about the unit in the socket. Another unit -- or the same
        // one after a round trip through the port -- was never asked about, so the rules have it
        // again without anything having to notice the swap.
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        engine.lock(STICK.sysfs, STICK.devnum);
        assertTrue(engine.isLocked(STICK.sysfs, STICK.devnum));
        assertFalse(engine.isLocked(STICK.sysfs, STICK.devnum + 1));
        assertFalse(engine.isCandidate(STICK));
        assertTrue(engine.isCandidate(replugged(STICK)));
        assertEquals(1, engine.plan(List.of(replugged(STICK)), allRunning(), anyController())
            .size());
        // And the inventory noticing the node go clears it outright.
        engine.forget(STICK.sysfs);
        assertFalse(engine.isLocked(STICK.sysfs, STICK.devnum));
    }

    @Test
    public void aVmAnAttachFailedForIsPassedOverUntilReplug() {
        var engine = engine(rules(Layer.ANY, List.of(
            rule(null, null, VM_A),
            rule(null, null, VM_B)
        )));
        engine.markFailed(STICK.sysfs, VM_A);
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.ANY, 1, VM_B);
        // The failure is the stick's alone.
        assertDecision(engine.decide(MOUSE, allRunning(), anyController()), MOUSE, Layer.ANY, 0, VM_A);
        // Failing for the only VM drops through to the fifth zone, so the device is the host's
        // rather than nobody's while that VM is out of the running.
        engine.setRules(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        assertDefaultHost(engine.decide(STICK, allRunning(), anyController()), STICK);
        // Unplugging clears it.
        engine.forget(STICK.sysfs);
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.ANY, 0, VM_A);
    }

    @Test
    public void aFailureIsForgottenWhenThatVmComesUpAgain() {
        // The reboot case: REBOOTING released the stick, RUNNING re-matched it, and the attach
        // failed once against the fresh instance. The next RUNNING edge must try again.
        var engine = engine(rules(Layer.ANY, List.of(
            rule(null, null, VM_A),
            rule(null, null, VM_B)
        )));
        engine.markFailed(STICK.sysfs, VM_A);
        engine.markFailed(MOUSE.sysfs, VM_B);
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.ANY, 1, VM_B);

        engine.forgetFailuresFor(VM_A);
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.ANY, 0, VM_A);
        // Only A's failures went; the mouse still remembers B and drops to the fifth zone.
        engine.setRules(rules(Layer.ANY, List.of(rule(null, null, VM_B))));
        assertDefaultHost(engine.decide(MOUSE, allRunning(), anyController()), MOUSE);
        // A lock is the user's and outlives any VM start.
        engine.lock(STICK.sysfs, STICK.devnum);
        engine.forgetFailuresFor(VM_A);
        assertTrue(engine.plan(List.of(STICK), allRunning(), anyController()).isEmpty());
    }

    @Test
    public void aDeviceIsDecidedAtMostOncePerPass() {
        var engine = engine(rules(
            Layer.EXACT, List.of(rule(STICK.id, STICK.port, VM_A)),
            Layer.PORT, List.of(rule(null, STICK.port, VM_B), rule(null, MOUSE.port, VM_B)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_C)),
            Layer.ANY, List.of(rule(null, null, VM_A), rule(null, null, VM_B))
        ));
        var plan = engine.plan(List.of(STICK, MOUSE), allRunning(), anyController());
        assertEquals(2, plan.size());
        assertNotEquals(plan.get(0).device.sysfs, plan.get(1).device.sysfs);
        assertDecision(plan.get(0), STICK, Layer.EXACT, 0, VM_A);
        assertDecision(plan.get(1), MOUSE, Layer.PORT, 1, VM_B);
    }

    @Test
    public void anAttachedDeviceIsNeverTakenAway() {
        // The stick is VM B's; a new rule set that wants it for VM A changes nothing for it.
        var engine = engine(rules(
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_A)),
            Layer.ANY, List.of(rule(null, null, VM_A))
        ));
        var plan = engine.plan(List.of(as(STICK, State.VMUSE), MOUSE), allRunning(),
            anyController());
        assertEquals(1, plan.size());
        assertDecision(plan.get(0), MOUSE, Layer.ANY, 0, VM_A);
    }

    @Test
    public void twoSerialLessUnitsShareAnIdAndBothMatchTheSameRule() {
        var first = device("1-1.3", "046d:c077");
        var second = device("1-1.4", "046d:c077");
        var engine = engine(rules(Layer.DEVICE, List.of(rule("046d:c077", null, VM_A))));
        var plan = engine.plan(Arrays.asList(first, second), allRunning(), anyController());
        assertEquals(2, plan.size());
        assertDecision(plan.get(0), first, Layer.DEVICE, 0, VM_A);
        assertDecision(plan.get(1), second, Layer.DEVICE, 0, VM_A);
    }

    @Test
    public void aDeviceCarriesWhatARuleCanSee() {
        var map = new EnumMap<Layer, List<Rule>>(Layer.class);
        map.put(Layer.EXACT, List.of(rule("090c:1000", "1.2.2", VM_A)));
        var engine = engine(UsbRules.build(map, null));
        // Same socket, USB3 enumeration: bus 2, and still the same port.
        var usb3 = device("2-1.2.2", "090c:1000");
        assertDecision(engine.decide(usb3, allRunning(), anyController()), usb3, Layer.EXACT, 0, VM_A);
        // Same model with a serial is a different id and misses the serial-less exact rule.
        assertDefaultHost(engine.decide(STICK, allRunning(), anyController()), STICK);
    }

    @Test
    public void aRuleWhoseControllerIsGoneIsPassedOverAndTheNextLayerWins() {
        // The device rule's VM is running and has the controller its rule names; the port rule's
        // does not, so the search goes on rather than stopping on a target it cannot reach.
        var engine = engine(rules(
            Layer.PORT, List.of(rule(null, STICK.port, VM_A, "xhci-1")),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_B, "xhci-0"))
        ));
        var have = controllers(Map.of(VM_A, List.of("xhci-0"), VM_B, List.of("xhci-0")));
        assertDecision(engine.decide(STICK, allRunning(), have), STICK, Layer.DEVICE, 0, VM_B,
            "xhci-0");
        // A dry run reports the rule that would act, not the one that cannot.
        var plan = engine.plan(List.of(STICK), allRunning(), have);
        assertEquals(1, plan.size());
        assertDecision(plan.get(0), STICK, Layer.DEVICE, 0, VM_B, "xhci-0");
        // Nothing was dropped: the rule is live again the moment the controller is back.
        var back = controllers(Map.of(VM_A, List.of("xhci-0", "xhci-1"), VM_B, List.of("xhci-0")));
        assertDecision(engine.decide(STICK, allRunning(), back), STICK, Layer.PORT, 0, VM_A,
            "xhci-1");
    }

    @Test
    public void aDecisionCarriesTheRulesControllerVerbatim() {
        var engine = engine(rules(
            Layer.PORT, List.of(rule(null, MOUSE.port, null)),
            Layer.ANY, List.of(rule(null, null, VM_A))
        ));
        // The host keeps the mouse, and a device the host keeps is on no controller.
        assertDecision(engine.decide(MOUSE, allRunning(), anyController()), MOUSE, Layer.PORT, 0,
            null, null);
        // A rule that names no controller stays null here rather than being resolved: which one
        // is "the first" is a property of the config the VM booted with, and the attach reads it.
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.ANY, 0,
            VM_A, null);
    }

    @Test
    public void aRuleNamingNoControllerStillNeedsTheVmToHaveOne() {
        var engine = engine(rules(Layer.ANY, List.of(
            rule(null, null, VM_A),
            rule(null, null, VM_B)
        )));
        var have = controllers(Map.of(VM_A, List.of(), VM_B, List.of("xhci-0")));
        assertDecision(engine.decide(STICK, allRunning(), have), STICK, Layer.ANY, 1, VM_B, null);
    }

    @Test
    public void aMissingControllerLeavesTheFailureMemoryAlone() {
        // Skipping is not failing: the same VM is still free to take the device through a rule it
        // can serve, which is why the controller check does not go through markFailed.
        var engine = engine(rules(
            Layer.PORT, List.of(rule(null, STICK.port, VM_A, "xhci-9")),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_A))
        ));
        var have = controllers(Map.of(VM_A, List.of("xhci-0")));
        assertDecision(engine.decide(STICK, allRunning(), have), STICK, Layer.DEVICE, 0, VM_A,
            null);
    }

    @Test
    public void aSinkStopsTheSearchTheWayAHostRuleDoes() {
        var engine = engine(rules(
            Layer.PORT, List.of(sink(null, STICK.port)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_A)),
            Layer.ANY, List.of(rule(null, null, VM_A))
        ));
        assertSink(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.PORT, 0);
        // It is a decision like any other, so it shows up in a plan and a dry run -- what makes
        // it a sink is that the pass does nothing with it.
        var plan = engine.plan(List.of(STICK, MOUSE), allRunning(), anyController());
        assertEquals(2, plan.size());
        assertSink(plan.get(0), STICK, Layer.PORT, 0);
        assertDecision(plan.get(1), MOUSE, Layer.ANY, 0, VM_A);
    }

    @Test
    public void aSinkNeedsNoVmToBeRunningAndNoFailureCanBlockIt() {
        // Leaving a device alone is something that is true here and now: there is no VM whose
        // state could hold it back, and a failure remembered against a VM is not its business.
        var engine = engine(rules(Layer.ANY, List.of(sink(null, null))));
        assertSink(engine.decide(STICK, states(), anyController()), STICK, Layer.ANY, 0);
        engine.markFailed(STICK.sysfs, VM_A);
        engine.markFailed(STICK.sysfs, VM_B);
        assertSink(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.ANY, 0);
        // And a VM rule that was passed over still lets the sink behind it have its turn.
        engine.setRules(rules(
            Layer.PORT, List.of(rule(null, STICK.port, VM_A)),
            Layer.DEVICE, List.of(sink(STICK.id, null))
        ));
        assertSink(engine.decide(STICK, states(VM_A, VMState.STOPPED), anyController()),
            STICK, Layer.DEVICE, 0);
    }

    @Test
    public void aSinkNeverTakesADeviceThatIsInUseOrLocked() {
        var engine = engine(rules(Layer.ANY, List.of(sink(null, null))));
        assertTrue(engine.plan(List.of(as(STICK, State.VMUSE)), allRunning(), anyController())
            .isEmpty());
        engine.lock(MOUSE.sysfs, MOUSE.devnum);
        assertTrue(engine.plan(List.of(MOUSE), allRunning(), anyController()).isEmpty());
    }

    // The master switch, trigger by trigger. Each of the five raises one of the two questions
    // this class answers, so switching the rules off is answered here once and for all of them.

    @Test
    public void aPlugTakesNothingWhileTheSwitchIsOff() {
        // Not even the fifth zone's own rule: with the switch off the host has the device
        // already -- the gate is open, so the kernel bound its drivers as it enumerated -- and
        // there is nothing for a pass to decide or to write.
        var engine = engine(disabled(Layer.ANY, List.of(sink(null, null))));
        assertNull(engine.decide(STICK, allRunning(), anyController()));
        assertTrue(engine.plan(List.of(STICK, MOUSE), allRunning(), anyController()).isEmpty());
    }

    @Test
    public void aVmReachingRunningTakesNothingWhileTheSwitchIsOff() {
        var engine = engine(disabled(Layer.DEVICE, List.of(rule(STICK.id, null, VM_A))));
        var running = states(VM_A, VMState.RUNNING);
        assertTrue(engine.plan(List.of(STICK), running, anyController()).isEmpty());
        assertNull(engine.decide(STICK, running, anyController()));
    }

    @Test
    public void aRulesSaveAppliesNothingWhileTheSwitchIsOff() {
        // The pass a save runs asks the same question, so a save made while the switch is off
        // keeps the rules and applies none of them.
        var engine = engine(rules(Layer.ANY, List.of(sink(null, null))));
        assertEquals(1, engine.plan(List.of(STICK), allRunning(), anyController()).size());
        engine.setRules(disabled(Layer.ANY, List.of(sink(null, null))));
        assertTrue(engine.plan(List.of(STICK), allRunning(), anyController()).isEmpty());
    }

    @Test
    public void aVmReleasingItsDevicesDecidesNothingWhileTheSwitchIsOff() {
        // A stop asks whether the rules want the device left alone; off, they want nothing, and
        // the device goes back to the host like any other.
        var engine = engine(disabled(Layer.DEVICE, List.of(sink(STICK.id, null))));
        assertNull(engine.decide(STICK, states(VM_A, VMState.STOPPED), anyController()));
    }

    @Test
    public void theDaemonStartPassDecidesWithNoVmRunningAndSkipsTheVmRules() {
        // The trigger the switch was added alongside: at start no VM is up, so a VM rule has
        // nothing to attach to. The sink leaves its device where the gate put it and the fifth
        // zone gives the other one to the host, which is what a keyboard needs to work.
        var engine = engine(rules(
            Layer.DEVICE, List.of(sink(STICK.id, null)),
            Layer.ANY, List.of(rule(null, null, VM_A))
        ));
        var plan = engine.plan(List.of(STICK, MOUSE), states(), anyController());
        assertEquals(2, plan.size());
        assertSink(plan.get(0), STICK, Layer.DEVICE, 0);
        assertDefaultHost(plan.get(1), MOUSE);
        // And nothing at all while the switch is off, which is what that pass is skipped for.
        engine.setRules(disabled(
            Layer.DEVICE, List.of(sink(STICK.id, null)),
            Layer.ANY, List.of(rule(null, null, VM_A))
        ));
        assertTrue(engine.plan(List.of(STICK, MOUSE), states(), anyController()).isEmpty());
    }

    @Test
    public void onlyTheSwitchesFallingEdgeGivesEverythingBack() {
        var on = rules(Layer.ANY, List.of(sink(null, null)));
        var off = disabled(Layer.ANY, List.of(sink(null, null)));
        var engine = engine(on);
        // On -> off, and only that, hands back what the rules took.
        assertEquals(Save.RELEASE, engine.saveOwes(off));
        engine.setRules(off);
        // Off -> off gives nothing back: the save that turned it off already did, once.
        assertEquals(Save.NOTHING,
            engine.saveOwes(disabled(Layer.DEVICE, List.of(sink(STICK.id, null)))));
        // Off -> on is the ordinary saved-rules pass, so turning it on applies them at once.
        assertEquals(Save.PASS, engine.saveOwes(on));
        engine.setRules(on);
        assertEquals(Save.PASS, engine.saveOwes(on));
    }

    @Test
    public void onlyTheDaemonStartPassHandsDevicesBackWhileTheSwitchIsOff() {
        // The gate is what leaves a device idle, and a pass is what hands one back. With the
        // rules running every pass may: the fifth zone answers for everything nothing else
        // matched. With them off no trigger acts at all -- an interface somebody unbound by
        // hand stays unbound, whatever is plugged in afterwards -- except the daemon's own
        // start, which is the only pass that runs to repair rather than to decide.
        var engine = engine(rules(Layer.ANY, List.of(sink(null, null))));
        assertTrue(engine.recoversIdleDevices(false));
        assertTrue(engine.recoversIdleDevices(true));
        engine.setRules(disabled(Layer.ANY, List.of(sink(null, null))));
        assertFalse(engine.recoversIdleDevices(false));
        assertTrue(engine.recoversIdleDevices(true));
    }

    @Test
    public void everyLockGoesBackWithTheDevicesWhenTheSwitchGoesOff() {
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        engine.lock(STICK.sysfs, STICK.devnum);
        engine.lock(MOUSE.sysfs, MOUSE.devnum);
        engine.markFailed(MOUSE.sysfs, VM_A);

        engine.clearLocks();
        assertFalse(engine.isLocked(STICK.sysfs, STICK.devnum));
        assertFalse(engine.isLocked(MOUSE.sysfs, MOUSE.devnum));
        // The stick is the rules' again; the mouse's failure is about an attach that did not
        // work, not about who owns the device, so it outlives the locks.
        var plan = engine.plan(List.of(STICK, MOUSE), allRunning(), anyController());
        assertEquals(2, plan.size());
        assertDecision(plan.get(0), STICK, Layer.ANY, 0, VM_A);
        assertDefaultHost(plan.get(1), MOUSE);
    }
}
