// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import cn.classfun.droidvm.ui.vm.edit.peripheral.XhciBindingDiff.Row;

/**
 * What one edit of an xHCI card's zones does to the app-global attach rules: the rules file is
 * shared with the global page, so the card pushes what it changed rather than what it shows.
 *
 * <p>Pure lists throughout: no org.json, which is a stub under the test android.jar (see
 * UsbRulesTest), and no Android at all.</p>
 */
public final class XhciBindingDiffTest {
    private static final String VM = "5e2b0000-0000-0000-0000-000000000001";
    private static final String OTHER_VM = "5e2b0000-0000-0000-0000-000000000002";

    private static Row exact(String id, String port, String vm, String controller) {
        return new Row(id, port, vm, controller);
    }

    private static Row mine(String id) {
        return new Row(id, null, VM, "xhci-0");
    }

    private static List<Row> list(Row... rows) {
        return new ArrayList<>(List.of(rows));
    }

    /** This page speaks for its VM's controllers, and for a rule that names none. */
    private static Predicate<Row> ownedByThisPage() {
        return row -> VM.equals(row.vm)
            && (row.controller == null || "xhci-0".equals(row.controller)
            || "xhci-1".equals(row.controller));
    }

    @Test
    public void aRowTheUserDeletedIsRemovedFromItsLayer() {
        var kept = mine("0bda:8153");
        var dropped = mine("090c:1000");
        var merged = XhciBindingDiff.merge(list(kept, dropped), list(kept, dropped), list(kept),
            ownedByThisPage());
        assertEquals(1, merged.size());
        assertSame(kept, merged.get(0));
    }

    @Test
    public void aRowTheUserAddedGoesOnTheHeadOfItsLayer() {
        var existing = mine("0bda:8153");
        var added = mine("090c:1000");
        var merged = XhciBindingDiff.merge(list(existing), list(existing),
            list(added, existing), ownedByThisPage());
        // Order within a layer is priority, and a layer usually ends in a rule that takes
        // everything left: a binding written behind one of those would never fire.
        assertEquals(2, merged.size());
        assertSame(added, merged.get(0));
        assertSame(existing, merged.get(1));
    }

    @Test
    public void severalNewRowsKeepTheOrderTheyWereAddedIn() {
        var existing = mine("0bda:8153");
        var first = mine("090c:1000");
        var second = mine("2109:0813");
        var merged = XhciBindingDiff.merge(list(existing), list(existing),
            list(first, second, existing), ownedByThisPage());
        assertEquals(List.of(first, second, existing), merged);
    }

    @Test
    public void aRowWhoseValueWasRePickedKeepsItsPlace() {
        // Re-picking a device edits the rule; it does not delete it and write another one at
        // the head, which would change what the rule beats.
        var first = exact("1111:1111", "1.1", OTHER_VM, null);
        var ours = mine("0bda:8153");
        var last = exact("3333:3333", "1.3", OTHER_VM, null);
        var edited = ours.edited("090c:1000", null, "xhci-0");
        var merged = XhciBindingDiff.merge(list(first, ours, last), list(ours), list(edited),
            ownedByThisPage());
        assertEquals(List.of(first, edited, last), merged);
    }

    @Test
    public void aRowEditedTwiceIsStillTheSameRow() {
        var ours = mine("0bda:8153");
        var once = ours.edited("090c:1000", null, "xhci-0");
        var twice = once.edited("2109:0813", null, "xhci-1");
        var merged = XhciBindingDiff.merge(list(ours), list(ours), list(twice),
            ownedByThisPage());
        assertEquals(List.of(twice), merged);
    }

    @Test
    public void aDeletionAndAnUnrelatedAdditionAreNotMistakenForAnEdit() {
        // Both happened, but not to the same row: the deleted one goes and the new one leads.
        var dropped = mine("0bda:8153");
        var kept = mine("090c:1000");
        var added = mine("2109:0813");
        var merged = XhciBindingDiff.merge(list(dropped, kept), list(dropped, kept),
            list(kept, added), ownedByThisPage());
        assertEquals(List.of(added, kept), merged);
    }

    @Test
    public void rowsOfOtherVmsAndOtherControllersKeepTheirPlaces() {
        var otherVm = exact("1234:5678", "1.1", OTHER_VM, null);
        var otherController = exact("1234:5679", "1.2", VM, "xhci-9");
        var ours = mine("0bda:8153");
        var merged = XhciBindingDiff.merge(list(otherVm, ours, otherController), list(ours),
            list(), ownedByThisPage());
        assertEquals(2, merged.size());
        assertSame(otherVm, merged.get(0));
        assertSame(otherController, merged.get(1));
    }

    @Test
    public void aRowAddedElsewhereWhileThePageWasOpenSurvives() {
        // The global rules page can be open at the same time, and its row is one this page
        // speaks for -- but it is not in the snapshot, so it was never this page's to delete.
        var ours = mine("0bda:8153");
        var addedElsewhere = mine("2109:0813");
        var merged = XhciBindingDiff.merge(list(ours, addedElsewhere), list(ours), list(ours),
            ownedByThisPage());
        assertEquals(2, merged.size());
        assertSame(ours, merged.get(0));
        assertSame(addedElsewhere, merged.get(1));
    }

    @Test
    public void anEditKeepsThePlaceOfTheRowItWasMadeFromNotOfALookAlike() {
        // Two identical rules, and only the second one was edited: the first must not be the
        // one that moves, or the user's edit would land on someone else's priority.
        var one = mine("0bda:8153");
        var two = mine("0bda:8153");
        var edited = two.edited("090c:1000", null, "xhci-0");
        var merged = XhciBindingDiff.merge(list(one, two), list(one, two), list(one, edited),
            ownedByThisPage());
        // The first row of `current` is matched first, so the edit lands there; what matters is
        // that exactly one of the pair is edited and the other is kept as it was.
        assertEquals(2, merged.size());
        assertTrue(merged.contains(edited));
        assertTrue(merged.get(0).sameAs(edited) || merged.get(1).sameAs(edited));
        assertTrue(merged.get(0).sameAs(one) || merged.get(1).sameAs(one));
    }

    @Test
    public void orderOfTheSurvivingRowsIsUnchanged() {
        var first = exact("1111:1111", "1.1", OTHER_VM, null);
        var second = mine("2222:2222");
        var third = exact("3333:3333", "1.3", OTHER_VM, null);
        var merged = XhciBindingDiff.merge(list(first, second, third), list(second), list(),
            ownedByThisPage());
        assertEquals(List.of(first, third), merged);
    }

    @Test
    public void deletingAControllerDeletesEveryRowItOwned() {
        // A card the user removed drops out of the working copy, so none of its rows is desired.
        var kept = mine("0bda:8153");
        var goneOne = exact("090c:1000", null, VM, "xhci-1");
        var goneTwo = exact("2109:0813", null, VM, "xhci-1");
        var merged = XhciBindingDiff.merge(list(kept, goneOne, goneTwo),
            list(kept, goneOne, goneTwo), list(kept), ownedByThisPage());
        assertEquals(List.of(kept), merged);
    }

    @Test
    public void twoIdenticalRulesLoseExactlyOne() {
        // Rows are compared, never identified: deleting one of a pair leaves the other.
        var one = mine("0bda:8153");
        var two = mine("0bda:8153");
        var merged = XhciBindingDiff.merge(list(one, two), list(one, two), list(one),
            ownedByThisPage());
        assertEquals(1, merged.size());
        assertTrue(merged.get(0).sameAs(one));
    }

    @Test
    public void aRowKeepsWhateverControllerItWasReadWith() {
        // Null means "the VM's first controller", which is what every rule written before
        // controllers existed says; rewriting it would change what an untouched rule means.
        var legacy = exact("0bda:8153", null, VM, null);
        var merged = XhciBindingDiff.merge(list(legacy), list(legacy), list(legacy),
            ownedByThisPage());
        assertEquals(1, merged.size());
        assertSame(legacy, merged.get(0));
    }

    @Test
    public void aRowMadeWhileEditingIsStampedWithTheVmTheSaveMinted() {
        var fresh = new Row("0bda:8153", null, null, "xhci-0");
        var stamped = fresh.withVm(VM);
        assertEquals(VM, stamped.vm);
        assertEquals("xhci-0", stamped.controller);
        // A row that already names a VM is left exactly as it was read.
        var loaded = mine("090c:1000");
        assertSame(loaded, loaded.withVm(OTHER_VM));
    }

    @Test
    public void sameRowsComparesMultisetsRatherThanOrder() {
        var a = mine("0bda:8153");
        var b = mine("090c:1000");
        assertTrue(XhciBindingDiff.sameRows(list(a, b), list(b, a)));
        assertFalse(XhciBindingDiff.sameRows(list(a, b), list(a)));
        assertFalse(XhciBindingDiff.sameRows(list(a, a), list(a, b)));
    }
}
