// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.classfun.droidvm.daemon.display.DaemonSystemContext;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;

/**
 * What the phone's own screen is, for sizing the VM's trackpad: how large it is and whether its
 * touch panel reports contact size.
 *
 * <p>Contact size is optional in Linux's multi-touch protocol, so a phone may have none; and a
 * panel can declare the axis with an empty range (0..0), which Android treats as absent. The
 * trackpad declares its contact size axes only when the panel has a real one, so a guest never
 * mistakes "not measured" for zero. Read from {@code getevent -lp} rather than from InputManager:
 * Android leaves a touchscreen's ranges out while the screen is off, and a VM may be started then.
 * Asked once per daemon; the panel does not change.</p>
 */
final class PhoneTouchPanel {
    private static final String TAG = "PhoneTouchPanel";
    private static final Pattern RANGE = Pattern.compile("min (-?\\d+), max (-?\\d+)");
    private static Boolean contactSize;

    private PhoneTouchPanel() {
    }

    /** Whether a direct multi-touch panel on the phone reports ABS_MT_TOUCH_MAJOR with a range. */
    static synchronized boolean reportsContactSize() {
        if (contactSize == null) contactSize = probeContactSize();
        return contactSize;
    }

    private static boolean probeContactSize() {
        try {
            var process = new ProcessBuilder("/system/bin/getevent", "-lp")
                .redirectErrorStream(true).start();
            boolean direct = false, multiTouch = false, size = false, found = false;
            try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("add device")) {
                        found |= direct && multiTouch && size;
                        direct = multiTouch = size = false;
                    } else if (line.contains("INPUT_PROP_DIRECT")) {
                        direct = true;
                    } else if (line.contains("ABS_MT_POSITION_X")) {
                        multiTouch = true;
                    } else if (line.contains("ABS_MT_TOUCH_MAJOR")) {
                        Matcher m = RANGE.matcher(line);
                        size = m.find() && Long.parseLong(m.group(2)) > Long.parseLong(m.group(1));
                    }
                }
            }
            found |= direct && multiTouch && size;
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy();
            Log.i(TAG, fmt("touch panel contact size: %s", found));
            return found;
        } catch (Exception e) {
            Log.w(TAG, "getevent failed; the trackpad goes without contact size", e);
            return false;
        }
    }

    /**
     * The trackpad's side in its units ({@link NativeDisplay#trackpadSideUnits}), from the phone's
     * built-in display; a 300 mm square when the daemon has no display to ask.
     */
    static int trackpadSideUnits() {
        var real = realMetrics();
        if (real != null) return NativeDisplay.trackpadSideUnits(real);
        return 300 * NativeDisplay.TRACKPAD_UNITS_PER_MM;
    }

    @Nullable
    private static DisplayMetrics realMetrics() {
        try {
            var context = DaemonSystemContext.get();
            if (context == null) return null;
            var display = context.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return null;
            var metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            return metrics.widthPixels > 0 ? metrics : null;
        } catch (Throwable t) {
            Log.w(TAG, "display metrics unavailable", t);
            return null;
        }
    }
}
