// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.migration;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.classfun.droidvm.display.INativeDisplayRootService;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.EvdevEncoder;

/** The host validates guest evdev output; Binder success alone is insufficient. */
final class CrosvmInputContract {
    static void verify(INativeDisplayRootService broker, String vm) throws Exception {
        assertFalse(broker.writeInput(vm, "unknown", 1, EvdevEncoder.encodeKey((short) 30, true)));
        assertFalse(broker.writeInput(vm + "-unknown", "main", 1, EvdevEncoder.encodeKey((short) 30, true)));
        assertFalse(broker.writeInput(vm, "main", 4, EvdevEncoder.encodeKey((short) 30, true)));
        assertFalse(broker.writeInput(vm, "main", 1, new byte[]{1, 2, 3}));
        for (int i = 0; i < 2; ++i) {
            String screen = i == 0 ? "main" : "aux";
            short key = (short) (i == 0 ? 30 : 48);
            assertTrue(broker.writeInput(vm, screen, 1, EvdevEncoder.encodeKey(key, true)));
            assertTrue(broker.writeInput(vm, screen, 1, EvdevEncoder.encodeKey(key, false)));
            assertTrue(broker.writeInput(vm, screen, 2, EvdevEncoder.encodeMouseMove(7 + i * 4, 9 + i * 4)));
            assertTrue(broker.writeInput(vm, screen, 3, EvdevEncoder.encodeAbsMove(100 + i * 200, 200 + i * 200)));
            var encoder = new EvdevEncoder();
            long now = SystemClock.uptimeMillis();
            var down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10 + i * 20, 20 + i * 20, 0);
            var up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 10 + i * 20, 20 + i * 20, 0);
            try {
                assertTrue(broker.writeInput(vm, screen, 0, encoder.encodeTouch(down, 1, 1)));
                assertTrue(broker.writeInput(vm, screen, 0, encoder.encodeTouch(up, 1, 1)));
            } finally {
                down.recycle();
                up.recycle();
            }
        }
        var result = new Bundle();
        result.putBoolean("real_input_submitted", true);
        InstrumentationRegistry.getInstrumentation().sendStatus(2, result);
    }
}
