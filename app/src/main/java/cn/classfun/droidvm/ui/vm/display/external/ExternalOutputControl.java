// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.external;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Menu;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Supplier;

import cn.classfun.droidvm.R;

/**
 * What both consoles -- native and VNC -- do about an external display, said once: the menu item
 * that sends the picture there or brings it back, which display when there are several, and what
 * the block standing in for the picture says.
 */
public final class ExternalOutputControl {
    private ExternalOutputControl() {
    }

    /** Whether [screenKey]'s picture is on an external display now. */
    public static boolean isExternal(@NonNull String screenKey) {
        return !screenKey.isEmpty()
            && VmOutputRouter.get().displayOf(screenKey) != Display.INVALID_DISPLAY;
    }

    /**
     * The picture to an external display, or back to the phone: one display goes straight there,
     * several are asked about, none is said.
     */
    public static void toggle(@NonNull Activity activity, @NonNull String screenKey,
                              @NonNull Supplier<VmOutputRouter.Screen> screen) {
        var router = VmOutputRouter.get();
        if (isExternal(screenKey)) {
            router.bringBack(activity, screenKey, true);
            return;
        }
        var displays = VmOutputRouter.externalDisplays(activity);
        if (displays.length == 0) {
            Toast.makeText(activity, R.string.external_output_none, Toast.LENGTH_SHORT).show();
            return;
        }
        if (displays.length == 1) {
            router.project(activity, screen.get(), displays[0]);
            return;
        }
        var names = new String[displays.length];
        for (int i = 0; i < displays.length; i++) names[i] = displays[i].getName();
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.external_output_pick)
            .setItems(names, (d, which) -> router.project(activity, screen.get(), displays[which]))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** The menu item [itemId] titled for where the picture is now. */
    public static void titleMenuItem(@NonNull Menu menu, int itemId, @NonNull String screenKey) {
        var item = menu.findItem(itemId);
        if (item != null)
            item.setTitle(isExternal(screenKey)
                ? R.string.external_output_stop : R.string.external_output_show);
    }

    /** What the block standing in for the picture says: which display the picture is on. */
    @NonNull
    public static String blockText(@NonNull Context context, int displayId) {
        var manager = context.getSystemService(DisplayManager.class);
        var display = manager == null ? null : manager.getDisplay(displayId);
        return context.getString(R.string.external_output_block,
            display == null ? String.valueOf(displayId) : display.getName());
    }
}
