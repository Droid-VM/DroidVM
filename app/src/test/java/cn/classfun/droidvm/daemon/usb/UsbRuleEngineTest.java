// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static org.junit.Assert.assertEquals;
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
import java.util.function.Function;

import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Decision;
import cn.classfun.droidvm.daemon.usb.UsbRuleEngine.Device;
import cn.classfun.droidvm.daemon.usb.UsbRules.Layer;
import cn.classfun.droidvm.daemon.usb.UsbRules.Rule;
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
        return new Rule(id, port, vm);
    }

    /** Only the layers named are populated; every VM id is taken on trust. */
    private static UsbRules rules(Object... layerAndList) {
        var map = new EnumMap<Layer, List<Rule>>(Layer.class);
        for (var i = 0; i < layerAndList.length; i += 2) {
            @SuppressWarnings("unchecked")
            var list = (List<Rule>) layerAndList[i + 1];
            map.put((Layer) layerAndList[i], list);
        }
        return UsbRules.build(map, null);
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
    }

    @Test
    public void nothingMatchesNothing() {
        var engine = engine(UsbRules.empty());
        assertNull(engine.decide(STICK, allRunning()));
        assertTrue(engine.plan(List.of(STICK, MOUSE), s -> false, allRunning()).isEmpty());
    }

    @Test
    public void layersAreConsultedInOrder() {
        var engine = engine(rules(
            Layer.EXACT, List.of(rule(STICK.id, STICK.port, VM_A)),
            Layer.PORT, List.of(rule(null, STICK.port, VM_B)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_C)),
            Layer.ANY, List.of(rule(null, null, VM_C))
        ));
        assertDecision(engine.decide(STICK, allRunning()), STICK, Layer.EXACT, 0, VM_A);

        engine.setRules(rules(
            Layer.PORT, List.of(rule(null, STICK.port, VM_B)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_C))
        ));
        assertDecision(engine.decide(STICK, allRunning()), STICK, Layer.PORT, 0, VM_B);

        engine.setRules(rules(Layer.DEVICE, List.of(rule(STICK.id, null, VM_C))));
        assertDecision(engine.decide(STICK, allRunning()), STICK, Layer.DEVICE, 0, VM_C);
    }

    @Test
    public void listOrderDecidesWithinALayer() {
        var engine = engine(rules(Layer.PORT, List.of(
            rule(null, STICK.port, VM_B),
            rule(null, STICK.port, VM_A)
        )));
        assertDecision(engine.decide(STICK, allRunning()), STICK, Layer.PORT, 0, VM_B);
    }

    @Test
    public void aRuleIsOnlyMatchedByItsOwnDevice() {
        var engine = engine(rules(
            Layer.EXACT, List.of(rule(STICK.id, "1.9", VM_A)),
            Layer.PORT, List.of(rule(null, "1.9", VM_A)),
            Layer.DEVICE, List.of(rule("ffff:ffff", null, VM_A))
        ));
        assertNull(engine.decide(STICK, allRunning()));
    }

    @Test
    public void aVmThatIsNotRunningIsPassedOver() {
        var engine = engine(rules(Layer.PORT, List.of(
            rule(null, STICK.port, VM_A),
            rule(null, STICK.port, VM_B)
        )));
        var states = states(VM_A, VMState.STOPPED, VM_B, VMState.RUNNING);
        assertDecision(engine.decide(STICK, states), STICK, Layer.PORT, 1, VM_B);
        // STARTING is not RUNNING either: the control socket is not there to attach through.
        states = states(VM_A, VMState.STARTING, VM_B, VMState.RUNNING);
        assertDecision(engine.decide(STICK, states), STICK, Layer.PORT, 1, VM_B);
        // A VM the store does not know cannot be running.
        states = states(VM_B, VMState.RUNNING);
        assertDecision(engine.decide(STICK, states), STICK, Layer.PORT, 1, VM_B);
        // And with nobody up, nothing happens.
        assertNull(engine.decide(STICK, states(VM_A, VMState.STOPPED, VM_B, VMState.STOPPED)));
    }

    @Test
    public void aLowerLayerTakesTheDeviceOfAPortRuleWhoseVmIsOff() {
        var engine = engine(rules(
            Layer.PORT, List.of(rule(null, STICK.port, VM_A)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_B))
        ));
        var states = states(VM_A, VMState.STOPPED, VM_B, VMState.RUNNING);
        assertDecision(engine.decide(STICK, states), STICK, Layer.DEVICE, 0, VM_B);
        // Once A is back, the port rule outranks the device rule again.
        assertDecision(engine.decide(STICK, allRunning()), STICK, Layer.PORT, 0, VM_A);
    }

    @Test
    public void aNullVmLeavesTheDeviceWithTheHostAndStopsTheSearch() {
        var engine = engine(rules(
            Layer.PORT, List.of(rule(null, MOUSE.port, null)),
            Layer.DEVICE, List.of(rule(MOUSE.id, null, VM_A)),
            Layer.ANY, List.of(rule(null, null, VM_A))
        ));
        assertDecision(engine.decide(MOUSE, allRunning()), MOUSE, Layer.PORT, 0, null);
        // The host reservation shows up in a plan, so a dry run can report it, with no VM.
        var plan = engine.plan(List.of(MOUSE, STICK), s -> false, allRunning());
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
        assertDecision(engine.decide(STICK, states), STICK, Layer.ANY, 1, VM_B);
        assertDecision(engine.decide(MOUSE, states), MOUSE, Layer.ANY, 1, VM_B);
    }

    @Test
    public void aHeldDeviceIsNotACandidate() {
        var engine = engine(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        engine.hold(STICK.sysfs);
        var plan = engine.plan(List.of(STICK, MOUSE), s -> false, allRunning());
        assertEquals(1, plan.size());
        assertDecision(plan.get(0), MOUSE, Layer.ANY, 0, VM_A);
        // A dry run still says what the rules would do with it.
        assertDecision(engine.decide(STICK, allRunning()), STICK, Layer.ANY, 0, VM_A);
        // A new rule set does not lift the hold.
        engine.setRules(rules(Layer.DEVICE, List.of(rule(STICK.id, null, VM_A))));
        assertTrue(engine.plan(List.of(STICK), s -> false, allRunning()).isEmpty());
        // Unplugging does.
        engine.forget(STICK.sysfs);
        assertEquals(1, engine.plan(List.of(STICK), s -> false, allRunning()).size());
    }

    @Test
    public void aVmAnAttachFailedForIsPassedOverUntilReplug() {
        var engine = engine(rules(Layer.ANY, List.of(
            rule(null, null, VM_A),
            rule(null, null, VM_B)
        )));
        engine.markFailed(STICK.sysfs, VM_A);
        assertDecision(engine.decide(STICK, allRunning()), STICK, Layer.ANY, 1, VM_B);
        // The failure is the stick's alone.
        assertDecision(engine.decide(MOUSE, allRunning()), MOUSE, Layer.ANY, 0, VM_A);
        // Failing for the only VM leaves the device where it is.
        engine.setRules(rules(Layer.ANY, List.of(rule(null, null, VM_A))));
        assertNull(engine.decide(STICK, allRunning()));
        engine.forget(STICK.sysfs);
        assertDecision(engine.decide(STICK, allRunning()), STICK, Layer.ANY, 0, VM_A);
    }

    @Test
    public void aDeviceIsDecidedAtMostOncePerPass() {
        var engine = engine(rules(
            Layer.EXACT, List.of(rule(STICK.id, STICK.port, VM_A)),
            Layer.PORT, List.of(rule(null, STICK.port, VM_B), rule(null, MOUSE.port, VM_B)),
            Layer.DEVICE, List.of(rule(STICK.id, null, VM_C)),
            Layer.ANY, List.of(rule(null, null, VM_A), rule(null, null, VM_B))
        ));
        var plan = engine.plan(List.of(STICK, MOUSE), s -> false, allRunning());
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
        var plan = engine.plan(List.of(STICK, MOUSE), STICK.sysfs::equals, allRunning());
        assertEquals(1, plan.size());
        assertDecision(plan.get(0), MOUSE, Layer.ANY, 0, VM_A);
    }

    @Test
    public void twoSerialLessUnitsShareAnIdAndBothMatchTheSameRule() {
        var first = device("1-1.3", "046d:c077");
        var second = device("1-1.4", "046d:c077");
        var engine = engine(rules(Layer.DEVICE, List.of(rule("046d:c077", null, VM_A))));
        var plan = engine.plan(Arrays.asList(first, second), s -> false, allRunning());
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
        assertDecision(engine.decide(usb3, allRunning()), usb3, Layer.EXACT, 0, VM_A);
        // Same model with a serial is a different id and misses the serial-less exact rule.
        assertNull(engine.decide(STICK, allRunning()));
    }
}
