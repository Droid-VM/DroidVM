// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.enums;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;

import java.util.List;

/**
 * What a picker does with a value it will not select.
 *
 * <p>That value arrives from a stored config rather than from a tap -- an option retired between
 * releases, or one belonging to the set some other row used to have -- and it used to leave as an
 * exception, which is how editing an old VM crashed the editor on open. Every case here is one a
 * config can actually be in; none of them touches the Context, which is why this runs without
 * inflating anything.</p>
 */
public class EnumPickerFallbackTest {
    /** A stand-in option set: a ladder, so "the head of the list" and "the default" can differ. */
    private enum Rung {
        CPU, GPU, ZERO, HW
    }

    private static EnumPicker<Rung> picker(Rung... items) {
        var picker = new EnumPicker<Rung>((Context) null, Rung.class);
        picker.setItems(items);
        return picker;
    }

    @Test
    public void unlistedValueFallsBackToTheHead() {
        var picker = picker(Rung.CPU, Rung.GPU);
        assertFalse(picker.setSelectedItem(Rung.ZERO));
        assertEquals(Rung.CPU, picker.getSelectedItem());
    }

    @Test
    public void namedDefaultTakesTheFallback() {
        var picker = picker(Rung.CPU, Rung.GPU, Rung.ZERO);
        picker.setDefaultItem(Rung.GPU);
        assertFalse(picker.setSelectedItem(Rung.HW));
        assertEquals(Rung.GPU, picker.getSelectedItem());
    }

    @Test
    public void refusedValueIsNotSelectedButStaysListed() {
        var picker = picker(Rung.CPU, Rung.GPU, Rung.ZERO);
        picker.setDefaultItem(Rung.GPU);
        picker.setDisabledItems(null, List.of(Rung.ZERO));
        assertFalse(picker.setSelectedItem(Rung.ZERO));
        assertEquals(Rung.GPU, picker.getSelectedItem());
        // Refused, not hidden: the menu still says the rung exists.
        assertTrue(picker.getItems().contains(Rung.ZERO));
        assertTrue(picker.setSelectedItem(Rung.CPU));
        assertEquals(Rung.CPU, picker.getSelectedItem());
    }

    @Test
    public void refusingTheCurrentValueMovesItOff() {
        var picker = picker(Rung.CPU, Rung.GPU, Rung.ZERO);
        picker.setDefaultItem(Rung.GPU);
        assertTrue(picker.setSelectedItem(Rung.ZERO));
        picker.setDisabledItems(null, List.of(Rung.ZERO));
        assertEquals(Rung.GPU, picker.getSelectedItem());
    }

    @Test
    public void defaultThatIsItselfRefusedFallsToTheFirstSelectable() {
        var picker = picker(Rung.CPU, Rung.GPU, Rung.ZERO);
        picker.setDefaultItem(Rung.ZERO);
        picker.setDisabledItems(null, List.of(Rung.CPU, Rung.ZERO));
        assertEquals(Rung.GPU, picker.getSelectedItem());
        assertFalse(picker.setSelectedItem(Rung.HW));
        assertEquals(Rung.GPU, picker.getSelectedItem());
    }

    @Test
    public void rotationSkipsRefusedRungs() {
        var picker = picker(Rung.CPU, Rung.GPU, Rung.ZERO);
        picker.setDisabledItems(null, List.of(Rung.GPU));
        assertEquals(Rung.CPU, picker.getSelectedItem());
        picker.selectNext();
        assertEquals(Rung.ZERO, picker.getSelectedItem());
        picker.selectNext();
        assertEquals(Rung.CPU, picker.getSelectedItem());
    }

    @Test
    public void aNewItemSetForgetsTheOldDefaultAndRefusals() {
        var picker = picker(Rung.CPU, Rung.GPU, Rung.ZERO);
        picker.setDefaultItem(Rung.ZERO);
        picker.setDisabledItems(null, List.of(Rung.CPU));
        picker.setItems(new Rung[]{Rung.CPU, Rung.GPU, Rung.HW});
        // Same constant, reachable under this set: nothing carries over from the last one.
        assertEquals(Rung.CPU, picker.getSelectedItem());
        assertTrue(picker.setSelectedItem(Rung.CPU));
        assertFalse(picker.setSelectedItem(Rung.ZERO));
        assertEquals(Rung.CPU, picker.getSelectedItem());
    }
}
