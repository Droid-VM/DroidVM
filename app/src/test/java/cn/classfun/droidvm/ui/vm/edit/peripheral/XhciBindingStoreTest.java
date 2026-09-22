// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.ui.usb.UsbRuleLayer;
import cn.classfun.droidvm.ui.vm.edit.peripheral.XhciBindingDiff.Row;

/**
 * The per-session copy of the attach rules behind an xHCI card's four zones: which rules a card
 * shows, what the zones do to the working copy, and when a save has anything to push.
 *
 * <p>The daemon's answer is handed in as rows rather than parsed here: org.json is a stub under
 * the test android.jar (see UsbRulesTest), so the JSON edge stays in the tab.</p>
 */
public final class XhciBindingStoreTest {
    private static final String VM = "5e2b0000-0000-0000-0000-000000000001";
    private static final String OTHER_VM = "5e2b0000-0000-0000-0000-000000000002";

    private static Map<UsbRuleLayer, List<Row>> rules(Row... rows) {
        var out = new EnumMap<UsbRuleLayer, List<Row>>(UsbRuleLayer.class);
        for (var layer : UsbRuleLayer.values()) out.put(layer, new ArrayList<>());
        for (var row : rows) {
            // The layer a row belongs to is the one whose fields it carries, which is exactly
            // how the daemon files it.
            var layer = row.id != null && row.port != null ? UsbRuleLayer.EXACT
                : row.port != null ? UsbRuleLayer.PORT
                : row.id != null ? UsbRuleLayer.DEVICE : UsbRuleLayer.ANY;
            out.get(layer).add(row);
        }
        return out;
    }

    @Test
    public void aRuleOfThisVmLandsInItsControllersZone() {
        var store = new XhciBindingStore();
        var row = new Row("0bda:8153", null, VM, "xhci-1");
        store.load(rules(row), List.of("xhci-0", "xhci-1"), VM);
        assertTrue(store.isLoaded());
        assertTrue(store.rows("xhci-0", UsbRuleLayer.DEVICE).isEmpty());
        assertEquals(List.of(row), store.rows("xhci-1", UsbRuleLayer.DEVICE));
    }

    @Test
    public void aRuleThatNamesNoControllerBelongsToTheFirstOne() {
        var store = new XhciBindingStore();
        var row = new Row("0bda:8153", null, VM, null);
        store.load(rules(row), List.of("xhci-0", "xhci-1"), VM);
        assertEquals(List.of(row), store.rows("xhci-0", UsbRuleLayer.DEVICE));
        assertTrue(store.rows("xhci-1", UsbRuleLayer.DEVICE).isEmpty());
    }

    @Test
    public void rulesOfAnotherVmOrAControllerThisOneLacksAreNotShown() {
        var store = new XhciBindingStore();
        var elsewhere = new Row("0bda:8153", null, OTHER_VM, "xhci-0");
        var dangling = new Row("090c:1000", null, VM, "xhci-7");
        var host = new Row("2109:0813", null, null, null, "host");
        var hidden = new Row("1a86:7523", null, null, null, "sink");
        store.load(rules(elsewhere, dangling, host, hidden), List.of("xhci-0"), VM);
        assertTrue(store.rows("xhci-0", UsbRuleLayer.DEVICE).isEmpty());
        // Not shown, and not this page's either: the merge must leave all four alone.
        assertFalse(store.owns(elsewhere, VM));
        assertFalse(store.owns(dangling, VM));
        assertFalse(store.owns(host, VM));
        assertFalse(store.owns(hidden, VM));
        assertFalse(store.isDirty());
    }

    @Test
    public void aVmThatDoesNotExistYetOwnsNothingAndEveryRowIsAnAddition() {
        var store = new XhciBindingStore();
        var someoneElses = new Row("0bda:8153", null, OTHER_VM, "xhci-0");
        store.load(rules(someoneElses), List.of("xhci-0"), null);
        assertTrue(store.snapshot(UsbRuleLayer.DEVICE).isEmpty());
        store.add("xhci-0", UsbRuleLayer.ANY, new Row(null, null, null, "xhci-0"));
        assertTrue(store.isDirty());
        assertEquals(1, store.desired(UsbRuleLayer.ANY).size());
    }

    @Test
    public void addingRemovingAndReplacingChangeOnlyTheWorkingCopy() {
        var store = new XhciBindingStore();
        var loaded = new Row("0bda:8153", null, VM, "xhci-0");
        store.load(rules(loaded), List.of("xhci-0"), VM);
        assertFalse(store.isDirty());

        var added = new Row("090c:1000", null, VM, "xhci-0");
        store.add("xhci-0", UsbRuleLayer.DEVICE, added);
        assertTrue(store.isDirty());
        assertEquals(List.of(loaded), store.snapshot(UsbRuleLayer.DEVICE));

        var replaced = new Row("2109:0813", null, VM, "xhci-0");
        store.replace("xhci-0", UsbRuleLayer.DEVICE, 0, replaced);
        assertEquals(List.of(replaced, loaded), store.rows("xhci-0", UsbRuleLayer.DEVICE));

        store.remove("xhci-0", UsbRuleLayer.DEVICE, 0);
        assertEquals(List.of(loaded), store.rows("xhci-0", UsbRuleLayer.DEVICE));
        assertFalse(store.isDirty());
    }

    @Test
    public void aRowAddedGoesToTheHeadOfItsZone() {
        // What the merge does to the file, so the zone shows the priority the save will write.
        var store = new XhciBindingStore();
        var existing = new Row("0bda:8153", null, VM, "xhci-0");
        store.load(rules(existing), List.of("xhci-0"), VM);
        var added = new Row("090c:1000", null, VM, "xhci-0");
        store.add("xhci-0", UsbRuleLayer.DEVICE, added);
        assertEquals(List.of(added, existing), store.rows("xhci-0", UsbRuleLayer.DEVICE));
        assertEquals(List.of(added, existing), store.desired(UsbRuleLayer.DEVICE));
    }

    @Test
    public void deletingACardTakesItsRowsWithItAndStillSpeaksForThem() {
        var store = new XhciBindingStore();
        var row = new Row("0bda:8153", null, VM, "xhci-1");
        store.load(rules(row), List.of("xhci-0", "xhci-1"), VM);
        store.removeController("xhci-1");
        assertTrue(store.rows("xhci-1", UsbRuleLayer.DEVICE).isEmpty());
        assertTrue(store.desired(UsbRuleLayer.DEVICE).isEmpty());
        // Still owned, or the merge would not recognise the row it has to delete.
        assertTrue(store.owns(row, VM));
        assertTrue(store.isDirty());
    }

    @Test
    public void aControllerAddedThisSessionIsOwnedFromTheStart() {
        var store = new XhciBindingStore();
        store.load(rules(), List.of("xhci-0"), VM);
        store.addController("xhci-1");
        var row = new Row(null, "1.2.2", VM, "xhci-1");
        assertTrue(store.owns(row, VM));
        store.add("xhci-1", UsbRuleLayer.PORT, row);
        assertEquals(List.of(row), store.rows("xhci-1", UsbRuleLayer.PORT));
    }

    @Test
    public void aPushThatLandedBecomesTheNewSnapshot() {
        var store = new XhciBindingStore();
        store.load(rules(), List.of("xhci-0"), VM);
        store.add("xhci-0", UsbRuleLayer.DEVICE, new Row("0bda:8153", null, VM, "xhci-0"));
        assertTrue(store.isDirty());
        store.markSaved();
        assertFalse(store.isDirty());
        assertEquals(1, store.snapshot(UsbRuleLayer.DEVICE).size());
    }

    @Test
    public void aRowAddedThisSessionIsSnapshottedWithTheVmItWasSentWith() {
        // The VM is named on the working rows before the push, not on a copy of them: a row
        // snapshotted without its VM could not be matched against the daemon's copy of itself,
        // and deleting it later in the same session would quietly do nothing.
        var store = new XhciBindingStore();
        store.load(rules(), List.of("xhci-0"), VM);
        store.add("xhci-0", UsbRuleLayer.DEVICE, new Row("0bda:8153", null, null, "xhci-0"));
        store.stampVm(VM);
        store.markSaved();
        assertFalse(store.isDirty());
        var asSent = store.snapshot(UsbRuleLayer.DEVICE).get(0);
        assertEquals(VM, asSent.vm);

        store.remove("xhci-0", UsbRuleLayer.DEVICE, 0);
        var merged = XhciBindingDiff.merge(new ArrayList<>(List.of(asSent)),
            store.snapshot(UsbRuleLayer.DEVICE), store.desired(UsbRuleLayer.DEVICE),
            row -> store.owns(row, VM));
        assertTrue(merged.isEmpty());
    }

    @Test
    public void nothingIsLoadedUntilTheDaemonAnswers() {
        // An unreachable daemon leaves the zones empty and the save with nothing to push, which
        // is what keeps a failed read from truncating the user's rules.
        var store = new XhciBindingStore();
        assertFalse(store.isLoaded());
        assertFalse(store.isDirty());
        assertTrue(store.rows("xhci-0", UsbRuleLayer.ANY).isEmpty());
    }
}
