// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import org.junit.Test;

import java.util.EnumMap;
import java.util.List;

import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.usb.UsbRules.Layer;
import cn.classfun.droidvm.daemon.usb.UsbRules.Rule;

/**
 * The validation a rule set goes through on the way in. The JSON edge is not covered: org.json
 * is a stub under the test android.jar (see UsbHostDeviceTest), so the model is built directly.
 */
public final class UsbRulesTest {
    private static final String VM = "0f4c9e2a-0000-4000-8000-00000000000a";
    private static final String XHCI0 = "xhci-0";
    private static final String XHCI1 = "xhci-1";

    private static UsbRules build(Layer layer, Rule rule) {
        var map = new EnumMap<Layer, List<Rule>>(Layer.class);
        map.put(layer, List.of(rule));
        return UsbRules.build(map, null);
    }

    private static String refused(Layer layer, Rule rule) {
        return assertThrows(RequestException.class, () -> build(layer, rule)).getMessage();
    }

    @Test
    public void emptyHasNoLayers() {
        var rules = UsbRules.empty();
        assertTrue(rules.isEmpty());
        for (var layer : Layer.values()) assertTrue(rules.layer(layer).isEmpty());
    }

    @Test
    public void eachLayerTakesExactlyItsOwnFields() {
        assertEquals(1, build(Layer.EXACT, new Rule("090c:1000", "1.2.2", VM, null)).layer(Layer.EXACT).size());
        assertEquals(1, build(Layer.PORT, new Rule(null, "1.6", null, null)).layer(Layer.PORT).size());
        assertEquals(1, build(Layer.DEVICE, new Rule("090c:1000:abc", null, VM, null)).layer(Layer.DEVICE).size());
        assertEquals(1, build(Layer.ANY, new Rule(null, null, VM, null)).layer(Layer.ANY).size());

        assertTrue(refused(Layer.EXACT, new Rule("090c:1000", null, VM, null)).contains("exact[0]: missing port"));
        assertTrue(refused(Layer.EXACT, new Rule(null, "1.2.2", VM, null)).contains("exact[0]: missing id"));
        assertTrue(refused(Layer.PORT, new Rule("090c:1000", "1.2.2", VM, null)).contains("takes no id"));
        assertTrue(refused(Layer.PORT, new Rule(null, null, VM, null)).contains("port[0]: missing port"));
        assertTrue(refused(Layer.DEVICE, new Rule("090c:1000", "1.2.2", VM, null)).contains("takes no port"));
        assertTrue(refused(Layer.DEVICE, new Rule(null, null, VM, null)).contains("device[0]: missing id"));
        assertTrue(refused(Layer.ANY, new Rule("090c:1000", null, VM, null)).contains("takes no id"));
        assertTrue(refused(Layer.ANY, new Rule(null, "1.2", VM, null)).contains("takes no port"));
    }

    @Test
    public void anyRefusesANullVm() {
        assertTrue(refused(Layer.ANY, new Rule(null, null, null, null)).contains("any[0]: vm must not be null"));
    }

    @Test
    public void theOtherLayersAcceptANullVmAsTheHost() {
        assertNull(build(Layer.EXACT, new Rule("090c:1000", "1.2.2", null, null)).layer(Layer.EXACT).get(0).vm);
        assertNull(build(Layer.PORT, new Rule(null, "1.2.2", null, null)).layer(Layer.PORT).get(0).vm);
        assertNull(build(Layer.DEVICE, new Rule("090c:1000", null, null, null)).layer(Layer.DEVICE).get(0).vm);
    }

    @Test
    public void anIdIsVidPidWithAnOptionalSerial() {
        assertTrue(refused(Layer.DEVICE, new Rule("090c-1000", null, VM, null)).contains("bad id"));
        assertTrue(refused(Layer.DEVICE, new Rule("90c:1000", null, VM, null)).contains("bad id"));
        assertTrue(refused(Layer.DEVICE, new Rule("090c:1000:", null, VM, null)).contains("bad id"));
        assertTrue(refused(Layer.DEVICE, new Rule("", null, VM, null)).contains("bad id"));
        // The hex is lowercased to what the device side reports; the serial is a string and is
        // kept as given, since that is how the device reports it too.
        var rule = build(Layer.DEVICE, new Rule("090C:1000:ABC-def", null, VM, null)).layer(Layer.DEVICE).get(0);
        assertEquals("090c:1000:ABC-def", rule.id);
        // A serial may itself contain a colon.
        assertEquals("0020:0b21:a:b", build(Layer.DEVICE, new Rule("0020:0b21:a:b", null, VM, null))
            .layer(Layer.DEVICE).get(0).id);
    }

    @Test
    public void aPortIsAChainOfNumbers() {
        assertEquals("1.2.2", build(Layer.PORT, new Rule(null, "1.2.2", VM, null)).layer(Layer.PORT).get(0).port);
        assertEquals("3", build(Layer.PORT, new Rule(null, "3", VM, null)).layer(Layer.PORT).get(0).port);
        assertTrue(refused(Layer.PORT, new Rule(null, "1-1.2", VM, null)).contains("bad port"));
        assertTrue(refused(Layer.PORT, new Rule(null, "1.", VM, null)).contains("bad port"));
        assertTrue(refused(Layer.PORT, new Rule(null, "", VM, null)).contains("bad port"));
    }

    @Test
    public void aVmMustExistWhenThereIsAStoreToAsk() {
        var map = new EnumMap<Layer, List<Rule>>(Layer.class);
        map.put(Layer.ANY, List.of(new Rule(null, null, VM, null)));
        var message = assertThrows(RequestException.class,
            () -> UsbRules.build(map, (vm, controller) -> false)).getMessage();
        assertTrue(message, message.contains(fmt("any[0]: VM not found: %s", VM)));
        assertEquals(VM, UsbRules.build(map, (vm, controller) -> VM.equals(vm))
            .layer(Layer.ANY).get(0).vm);
        // Without a store (a file read at start-up) the rule is kept as it is.
        assertEquals(VM, UsbRules.build(map, null).layer(Layer.ANY).get(0).vm);
        assertTrue(refused(Layer.ANY, new Rule(null, null, "", null)).contains("vm must be a VM id"));
    }

    @Test
    public void theErrorNamesTheLayerAndTheIndex() {
        var map = new EnumMap<Layer, List<Rule>>(Layer.class);
        map.put(Layer.PORT, List.of(new Rule(null, "1.2", VM, null), new Rule(null, "x", VM, null)));
        var message = assertThrows(RequestException.class,
            () -> UsbRules.build(map, null)).getMessage();
        assertTrue(message, message.startsWith("port[1]: bad port x"));
    }

    @Test
    public void aRuleMatchesOnWhicheverFieldsItHas() {
        assertTrue(new Rule("090c:1000", "1.2", VM, null).matches("090c:1000", "1.2"));
        assertFalse(new Rule("090c:1000", "1.2", VM, null).matches("090c:1000", "1.3"));
        assertFalse(new Rule("090c:1000", "1.2", VM, null).matches("090c:1001", "1.2"));
        assertTrue(new Rule(null, "1.2", VM, null).matches("ffff:ffff", "1.2"));
        assertTrue(new Rule("090c:1000", null, VM, null).matches("090c:1000", "9.9"));
        assertTrue(new Rule(null, null, VM, null).matches("ffff:ffff", "9.9"));
    }

    @Test
    public void aRuleCarriesItsControllerAsItIsGiven() {
        // The two shapes that go over the wire: a rule that names one of the VM's controllers,
        // and one that names none -- which is every rule written before controllers existed, and
        // still means "the target VM's first xHCI".
        var named = build(Layer.EXACT, new Rule("090c:1000", "1.2.2", VM, XHCI1))
            .layer(Layer.EXACT).get(0);
        assertEquals(XHCI1, named.controller);
        assertEquals(VM, named.vm);
        assertNull(build(Layer.EXACT, new Rule("090c:1000", "1.2.2", VM, null))
            .layer(Layer.EXACT).get(0).controller);
        // It rides along with the fields the layer does read, id lowercasing included.
        var device = build(Layer.DEVICE, new Rule("090C:1000", null, VM, XHCI0))
            .layer(Layer.DEVICE).get(0);
        assertEquals("090c:1000", device.id);
        assertEquals(XHCI0, device.controller);
        assertEquals(XHCI0, build(Layer.ANY, new Rule(null, null, VM, XHCI0))
            .layer(Layer.ANY).get(0).controller);
    }

    @Test
    public void aHostRuleCarriesNoController() {
        // "Keep it on the host" has no VM, so there is nothing for a controller to be inside of.
        assertTrue(refused(Layer.PORT, new Rule(null, "1.2.2", null, XHCI0))
            .contains("port[0]: controller needs a vm"));
    }

    @Test
    public void anEmptyControllerIsRefused() {
        // "" is not "no controller": a rule that means the VM's first one leaves the key out.
        assertTrue(refused(Layer.ANY, new Rule(null, null, VM, ""))
            .contains("any[0]: controller must be a controller id or null"));
    }

    @Test
    public void aControllerMustExistWhenThereIsAStoreToAsk() {
        // The model's contract, asked with a hand-written predicate. What the daemon supplies
        // answers on the VM alone (UsbPassthroughManager.targetExists says why), so this is the
        // shape a caller with a current copy of the config would use, not today's refusal.
        var map = new EnumMap<Layer, List<Rule>>(Layer.class);
        map.put(Layer.ANY, List.of(new Rule(null, null, VM, XHCI1)));
        var message = assertThrows(RequestException.class,
            () -> UsbRules.build(map, (vm, controller) -> XHCI0.equals(controller))).getMessage();
        assertTrue(message, message.contains(fmt("any[0]: VM %s has no USB controller %s",
            VM, XHCI1)));
        // The unknown-VM wording is kept for a rule that names no controller: nothing about the
        // controller is wrong there.
        var vmOnly = new EnumMap<Layer, List<Rule>>(Layer.class);
        vmOnly.put(Layer.ANY, List.of(new Rule(null, null, VM, null)));
        assertTrue(assertThrows(RequestException.class,
            () -> UsbRules.build(vmOnly, (vm, controller) -> false)).getMessage()
            .contains(fmt("any[0]: VM not found: %s", VM)));
        // A target the predicate accepts is kept as it is.
        assertEquals(XHCI1, UsbRules.build(map, (vm, controller) -> true)
            .layer(Layer.ANY).get(0).controller);
        // And with no store to ask -- the file read at start-up -- neither half is checked, so a
        // rule for a controller deleted since is kept rather than dropped behind the user's back.
        assertEquals(XHCI1, UsbRules.build(map, null).layer(Layer.ANY).get(0).controller);
    }
}
