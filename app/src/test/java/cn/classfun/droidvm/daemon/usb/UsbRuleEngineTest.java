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

import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Decision;
import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Device;
import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Pin;
import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Save;
import cn.classfun.droidvm.daemon.usb.UsbRules.Layer;
import cn.classfun.droidvm.daemon.usb.UsbRules.Rule;
import cn.classfun.droidvm.daemon.usb.UsbRules.Target;
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * The matching, over hand-built devices and a table of VM states: no sysfs, no crosvm. The
 * manager's part -- taking the lock, attaching, broadcasting -- is not exercised here.
 */
public final class UsbRuleEngineTest {
    private static final String VM_A = "0f4c9e2a-0000-4000-8000-00000000000a";
    private static final String VM_B = "0f4c9e2a-0000-4000-8000-00000000000b";
    private static final String VM_C = "0f4c9e2a-0000-4000-8000-00000000000c";
    /** A serialised flash drive on the hub's second downstream port, second hop. */
    private static final Device STICK = device("1-1.2.2", "090c:1000:0123456789ABCDEF");
    /** A serial-less mouse on port 1.6. */
    private static final Device MOUSE = device("1-1.6", "046d:c077");

    private static Device device(String sysfs, String id) {
        return new Device(sysfs, id, UsbHostDevice.derivePort(sysfs));
    }

    private static Rule rule(String id, String port, String vm) {
        return new Rule(id, port, vm, null);
    }

    /** A rule that names one controller inside its target VM. */
    private static Rule rule(String id, String port, String vm, String controller) {
        return new Rule(id, port, vm, controller);
    }

    /** A rule that hides whatever it matches. */
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

    private static void assertDecision(Decision decision, Device device, Layer layer, int index,
                                       String vm, String controller) {
        assertDecision(decision, device, layer, index, vm);
        assertEquals(controller, decision.controller);
    }

    @Test
    public void nothingMatchesNothing() {
        var engine = engine(UsbRules.empty());
        assertNull(engine.decide(STICK, allRunning(), anyController()));
        assertTrue(engine.plan(List.of(STICK, MOUSE), s -> false, allRunning(), anyController()).isEmpty());
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
        assertNull(engine.decide(STICK, allRunning(), anyController()));
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
        // And with nobody up, nothing happens.
        assertNull(engine.decide(STICK, states(VM_A, VMState.STOPPED, VM_B, VMState.STOPPED),
            anyController()));
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
        // The host reservation shows up in a plan, so a dry run can report it, with no VM.
        var plan = engine.plan(List.of(MOUSE, STICK), s -> false, allRunning(), anyController());
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

    @Test
    public void aHeldDeviceIsNotACandidate() {
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        engine.pin(STICK.sysfs, Pin.HOST);
        var plan = engine.plan(List.of(STICK, MOUSE), s -> false, allRunning(), anyController());
        assertEquals(1, plan.size());
        assertDecision(plan.get(0), MOUSE, Layer.ANY, 0, VM_A);
        // A dry run still says what the rules would do with it.
        assertDecision(engine.decide(STICK, allRunning(), anyController()), STICK, Layer.ANY, 0, VM_A);
        // A new rule set does not lift the hold.
        engine.setRules(rules(Layer.DEVICE, List.of(rule(STICK.id, null, VM_A))));
        assertTrue(engine.plan(List.of(STICK), s -> false, allRunning(), anyController()).isEmpty());
        // Unplugging does.
        engine.forget(STICK.sysfs);
        assertEquals(1, engine.plan(List.of(STICK), s -> false, allRunning(), anyController()).size());
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
        // Failing for the only VM leaves the device where it is; a rules save changes nothing.
        engine.setRules(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        assertNull(engine.decide(STICK, allRunning(), anyController()));
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
        // Only A's failures went; the mouse still remembers B.
        engine.setRules(rules(Layer.ANY, List.of(rule(null, null, VM_B))));
        assertNull(engine.decide(MOUSE, allRunning(), anyController()));
        // A hold is the user's and outlives any VM start.
        engine.pin(STICK.sysfs, Pin.HOST);
        engine.forgetFailuresFor(VM_A);
        assertTrue(engine.plan(List.of(STICK), s -> false, allRunning(), anyController()).isEmpty());
    }

    @Test
    public void aDeviceIsDecidedAtMostOncePerPass() {
        var engine = engine(rules(
            Layer.EXACT, List.of(rule(STICK.id, STICK.port, VM_A)),
            Layer.PORT, List.of(rule(null, STICK.port, VM_B), rule(null, MOUSE.port, VM_B)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_C)),
            Layer.ANY, List.of(rule(null, null, VM_A), rule(null, null, VM_B))
        ));
        var plan = engine.plan(List.of(STICK, MOUSE), s -> false, allRunning(), anyController());
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
        var plan = engine.plan(List.of(STICK, MOUSE), STICK.sysfs::equals, allRunning(), anyController());
        assertEquals(1, plan.size());
        assertDecision(plan.get(0), MOUSE, Layer.ANY, 0, VM_A);
    }

    @Test
    public void twoSerialLessUnitsShareAnIdAndBothMatchTheSameRule() {
        var first = device("1-1.3", "046d:c077");
        var second = device("1-1.4", "046d:c077");
        var engine = engine(rules(Layer.DEVICE, List.of(rule("046d:c077", null, VM_A))));
        var plan = engine.plan(Arrays.asList(first, second), s -> false, allRunning(), anyController());
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
        assertNull(engine.decide(STICK, allRunning(), anyController()));
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
        var plan = engine.plan(List.of(STICK), s -> false, allRunning(), have);
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
        // It is an action, so it shows up in a plan and a dry run like any other decision.
        var plan = engine.plan(List.of(STICK, MOUSE), s -> false, allRunning(), anyController());
        assertEquals(2, plan.size());
        assertSink(plan.get(0), STICK, Layer.PORT, 0);
        assertDecision(plan.get(1), MOUSE, Layer.ANY, 0, VM_A);
    }

    @Test
    public void aSinkNeedsNoVmToBeRunningAndNoFailureCanBlockIt() {
        // Deauthorizing is a one-byte write to sysfs: there is no VM whose state could hold it
        // back, and a failure remembered against a VM is not the sink's business.
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
    public void aSinkNeverTakesADeviceThatIsAttachedOrPinned() {
        var engine = engine(rules(Layer.ANY, List.of(sink(null, null))));
        assertTrue(engine.plan(List.of(STICK), STICK.sysfs::equals, allRunning(), anyController())
            .isEmpty());
        engine.pin(MOUSE.sysfs, Pin.HOST);
        assertTrue(engine.plan(List.of(MOUSE), s -> false, allRunning(), anyController())
            .isEmpty());
    }

    @Test
    public void bothPinsHoldADeviceAndOnlyAnUnplugClearsThem() {
        // "Held" is what it always was -- a device no trigger may touch -- and it is now the
        // answer to two questions: the user took it back, or the user hid it.
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        assertFalse(engine.isHeld(STICK.sysfs));
        assertEquals(Pin.NONE, engine.pinOf(STICK.sysfs));

        engine.pin(STICK.sysfs, Pin.HOST);
        assertEquals(Pin.HOST, engine.pinOf(STICK.sysfs));
        assertTrue(engine.isHeld(STICK.sysfs));
        assertTrue(engine.plan(List.of(STICK), s -> false, allRunning(), anyController())
            .isEmpty());

        engine.pin(STICK.sysfs, Pin.SINK);
        assertEquals(Pin.SINK, engine.pinOf(STICK.sysfs));
        assertTrue(engine.isHeld(STICK.sysfs));
        assertTrue(engine.plan(List.of(STICK), s -> false, allRunning(), anyController())
            .isEmpty());
        // A rules save does not lift either of them; pulling the cable does.
        engine.setRules(rules(Layer.DEVICE, List.of(rule(STICK.id, null, VM_A))));
        assertTrue(engine.plan(List.of(STICK), s -> false, allRunning(), anyController())
            .isEmpty());
        engine.forget(STICK.sysfs);
        assertEquals(Pin.NONE, engine.pinOf(STICK.sysfs));
        assertEquals(1, engine.plan(List.of(STICK), s -> false, allRunning(), anyController())
            .size());
    }

    @Test
    public void aVmComingUpClearsItsFailuresAndLeavesEveryPinWhereItIs() {
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        engine.markFailed(STICK.sysfs, VM_A);
        engine.pin(STICK.sysfs, Pin.SINK);
        engine.markFailed(MOUSE.sysfs, VM_A);

        engine.forgetFailuresFor(VM_A);
        // The mouse's failure went with the VM; the stick's pin is the user's and stays.
        assertDecision(engine.decide(MOUSE, allRunning(), anyController()), MOUSE, Layer.ANY, 0,
            VM_A);
        assertEquals(Pin.SINK, engine.pinOf(STICK.sysfs));
        assertTrue(engine.plan(List.of(STICK), s -> false, allRunning(), anyController())
            .isEmpty());
    }

    // The master switch, trigger by trigger. Each of the five raises one of the two questions
    // this class answers, so switching the rules off is answered here once and for all of them.

    @Test
    public void aPlugTakesNothingWhileTheSwitchIsOff() {
        // The fast lane's whole question, asked on the FileObserver thread before any debounce:
        // no rule takes the device, so nothing is deauthorized ahead of Android...
        var engine = engine(disabled(Layer.ANY, List.of(sink(null, null))));
        assertNull(engine.decide(STICK, allRunning(), anyController()));
        // ... and the debounced pass 700 ms behind it plans nothing either.
        assertTrue(engine.plan(List.of(STICK, MOUSE), s -> false, allRunning(), anyController())
            .isEmpty());
    }

    @Test
    public void aVmReachingRunningTakesNothingWhileTheSwitchIsOff() {
        var engine = engine(disabled(Layer.DEVICE, List.of(rule(STICK.id, null, VM_A))));
        var running = states(VM_A, VMState.RUNNING);
        assertTrue(engine.plan(List.of(STICK), s -> false, running, anyController()).isEmpty());
        assertNull(engine.decide(STICK, running, anyController()));
    }

    @Test
    public void aRulesSaveAppliesNothingWhileTheSwitchIsOff() {
        // The pass a save runs asks the same question, so a save made while the switch is off
        // keeps the rules and applies none of them.
        var engine = engine(rules(Layer.ANY, List.of(sink(null, null))));
        assertEquals(1, engine.plan(List.of(STICK), s -> false, allRunning(), anyController())
            .size());
        engine.setRules(disabled(Layer.ANY, List.of(sink(null, null))));
        assertTrue(engine.plan(List.of(STICK), s -> false, allRunning(), anyController())
            .isEmpty());
    }

    @Test
    public void aVmReleasingItsDevicesHidesNothingWhileTheSwitchIsOff() {
        // A stop asks whether the rules want the device hidden rather than handed back; off,
        // they want nothing, and the device goes back to the host like any other.
        var engine = engine(disabled(Layer.DEVICE, List.of(sink(STICK.id, null))));
        assertNull(engine.decide(STICK, states(VM_A, VMState.STOPPED), anyController()));
    }

    @Test
    public void theDaemonStartPassSinksWithNoVmRunningAndSkipsTheVmRules() {
        // The trigger the switch was added alongside: at start no VM is up, so a VM rule has
        // nothing to attach to and only the sink acts -- which is the device that must be gone
        // before Android settles.
        var engine = engine(rules(
            Layer.DEVICE, List.of(sink(STICK.id, null)),
            Layer.ANY, List.of(rule(null, null, VM_A))
        ));
        var plan = engine.plan(List.of(STICK, MOUSE), s -> false, states(), anyController());
        assertEquals(1, plan.size());
        assertSink(plan.get(0), STICK, Layer.DEVICE, 0);
        // And nothing at all while the switch is off, which is what that pass is skipped for.
        engine.setRules(disabled(
            Layer.DEVICE, List.of(sink(STICK.id, null)),
            Layer.ANY, List.of(rule(null, null, VM_A))
        ));
        assertTrue(engine.plan(List.of(STICK, MOUSE), s -> false, states(), anyController())
            .isEmpty());
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
    public void everyPinGoesBackWithTheDevicesWhenTheSwitchGoesOff() {
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        engine.pin(STICK.sysfs, Pin.SINK);
        engine.pin(MOUSE.sysfs, Pin.HOST);
        engine.markFailed(MOUSE.sysfs, VM_A);

        engine.clearPins();
        assertEquals(Pin.NONE, engine.pinOf(STICK.sysfs));
        assertEquals(Pin.NONE, engine.pinOf(MOUSE.sysfs));
        assertFalse(engine.isHeld(STICK.sysfs));
        // The stick is the rules' again; the mouse's failure is about an attach that did not
        // work, not about who owns the device, so it outlives the pins.
        var plan = engine.plan(List.of(STICK, MOUSE), s -> false, allRunning(), anyController());
        assertEquals(1, plan.size());
        assertDecision(plan.get(0), STICK, Layer.ANY, 0, VM_A);
    }
}
