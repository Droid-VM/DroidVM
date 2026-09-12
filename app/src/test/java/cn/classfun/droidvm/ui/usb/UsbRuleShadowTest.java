// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static org.junit.Assert.assertArrayEquals;

import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * Which rule cards the page draws as unreachable. Rows are {@link DataItem}, whose org.json
 * conversion stays at its edges (see VMXhciMigrationTest), so the zone's list is buildable here
 * under the stubbed android.jar.
 */
public final class UsbRuleShadowTest {
    @Test
    public void aHostRuleShadowsTheSameMatcherBelowIt() {
        var rules = List.of(
            device("0bda:8153", "host"),
            device("0bda:8153", "vm"));
        assertArrayEquals(new boolean[]{false, true},
            UsbRuleShadow.unreachable(UsbRuleLayer.DEVICE, rules));
    }

    @Test
    public void aSinkRuleShadowsJustAsAHostRuleDoes() {
        var rules = List.of(
            device("0bda:8153", "sink"),
            device("0bda:8153", "vm"),
            device("0bda:8153", "host"));
        assertArrayEquals(new boolean[]{false, true, true},
            UsbRuleShadow.unreachable(UsbRuleLayer.DEVICE, rules));
    }

    @Test
    public void aVmRuleNeverShadows() {
        // The VM may be stopped, may have lost the controller, or may refuse the device, and
        // then the rule below is what runs -- which is the reason to write one.
        var rules = List.of(
            device("0bda:8153", "vm"),
            device("0bda:8153", "vm"),
            device("0bda:8153", "host"));
        assertArrayEquals(new boolean[]{false, false, false},
            UsbRuleShadow.unreachable(UsbRuleLayer.DEVICE, rules));
    }

    @Test
    public void aDifferentMatcherIsNotShadowed() {
        var rules = List.of(
            device("0bda:8153", "host"),
            device("1a86:7523", "vm"));
        assertArrayEquals(new boolean[]{false, false},
            UsbRuleShadow.unreachable(UsbRuleLayer.DEVICE, rules));
    }

    @Test
    public void eachMatcherIsItsOwnSearch() {
        // Two devices spoken for in one zone: the second host rule is reached, and each of them
        // shadows only the rows about its own device.
        var rules = List.of(
            device("0bda:8153", "host"),
            device("1a86:7523", "host"),
            device("0bda:8153", "vm"),
            device("1a86:7523", "vm"));
        assertArrayEquals(new boolean[]{false, false, true, true},
            UsbRuleShadow.unreachable(UsbRuleLayer.DEVICE, rules));
    }

    @Test
    public void theExactZoneComparesBothTheDeviceAndThePort() {
        var rules = List.of(
            exact("0bda:8153", "1.2.1", "host"),
            exact("0bda:8153", "1.2.2", "vm"),
            exact("1a86:7523", "1.2.1", "vm"),
            exact("0bda:8153", "1.2.1", "vm"));
        assertArrayEquals(new boolean[]{false, false, false, true},
            UsbRuleShadow.unreachable(UsbRuleLayer.EXACT, rules));
    }

    @Test
    public void thePortZoneComparesThePortAlone() {
        // Whatever else a row carries, a port rule matches on its port -- so these two rules
        // are about the same socket however differently they were written.
        var rules = List.of(
            port("1.2.1", "host"),
            exact("0bda:8153", "1.2.1", "vm"));
        assertArrayEquals(new boolean[]{false, true},
            UsbRuleShadow.unreachable(UsbRuleLayer.PORT, rules));
    }

    @Test
    public void theCatchAllZoneCannotHitTheCase() {
        // The page lets that zone hold one row, and one row shadows nothing.
        assertArrayEquals(new boolean[]{false},
            UsbRuleShadow.unreachable(UsbRuleLayer.ANY, List.of(rule(null, null, "sink"))));
        assertArrayEquals(new boolean[]{},
            UsbRuleShadow.unreachable(UsbRuleLayer.ANY, List.of()));
    }

    @Test
    public void aRowWrittenBeforeTargetsExistedShadowsAsTheHostRuleItMeans() {
        var rules = List.of(
            rule("0bda:8153", null, null),
            device("0bda:8153", "vm"));
        assertArrayEquals(new boolean[]{false, true},
            UsbRuleShadow.unreachable(UsbRuleLayer.DEVICE, rules));
    }

    // Rows in the shape the page stores them: only the fields its zone carries, plus the target.

    private static DataItem device(String id, String target) {
        return rule(id, null, target);
    }

    private static DataItem port(String port, String target) {
        return rule(null, port, target);
    }

    private static DataItem exact(String id, String port, String target) {
        return rule(id, port, target);
    }

    private static DataItem rule(@Nullable String id, @Nullable String port,
                                 @Nullable String target) {
        var item = DataItem.newObject();
        if (id != null) item.set("id", id);
        if (port != null) item.set("port", port);
        if (target != null) item.set("target", target);
        // A VM rule names one; nothing here reads which, only that the target is not final.
        if ("vm".equals(target)) item.set("vm", "vm-1");
        return item;
    }
}
