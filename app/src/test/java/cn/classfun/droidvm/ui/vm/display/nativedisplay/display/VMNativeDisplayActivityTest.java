// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;
import android.view.MotionEvent;

import org.junit.Test;

public final class VMNativeDisplayActivityTest {
    @Test
    public void physicalMouseSourceAcceptsNormalAndCapturedMouseEvents() {
        assertTrue(VMNativeDisplayActivity.isPhysicalMouseSource(InputDevice.SOURCE_MOUSE));
        assertTrue(VMNativeDisplayActivity.isPhysicalMouseSource(
            InputDevice.SOURCE_MOUSE_RELATIVE));
        assertFalse(VMNativeDisplayActivity.isPhysicalMouseSource(InputDevice.SOURCE_TOUCHSCREEN));
    }

    @Test
    public void mouseCancelReleasesEveryButton() {
        int all = MotionEvent.BUTTON_PRIMARY
            | MotionEvent.BUTTON_SECONDARY
            | MotionEvent.BUTTON_TERTIARY;
        assertEquals(0, VMNativeDisplayActivity.physicalMouseButtonsForEvent(
            MotionEvent.ACTION_CANCEL, all, 0));
    }

    @Test
    public void mouseDownWithoutButtonMetadataStillPressesPrimary() {
        assertEquals(MotionEvent.BUTTON_PRIMARY,
            VMNativeDisplayActivity.physicalMouseButtonsForEvent(
                MotionEvent.ACTION_DOWN, 0, 0));
    }

    @Test
    public void actionButtonCorrectsStaleButtonState() {
        assertEquals(MotionEvent.BUTTON_SECONDARY,
            VMNativeDisplayActivity.physicalMouseButtonsForEvent(
                MotionEvent.ACTION_BUTTON_PRESS, 0, MotionEvent.BUTTON_SECONDARY));
        assertEquals(0, VMNativeDisplayActivity.physicalMouseButtonsForEvent(
            MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.BUTTON_SECONDARY,
            MotionEvent.BUTTON_SECONDARY));
    }
}
