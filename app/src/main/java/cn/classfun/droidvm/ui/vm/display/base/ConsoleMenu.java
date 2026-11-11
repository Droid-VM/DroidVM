// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import android.app.Activity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.usb.UsbPassthroughDialog;
import cn.classfun.droidvm.ui.vm.display.external.ExternalOutputControl;
import cn.classfun.droidvm.ui.vm.display.external.VmOutputRouter;

/**
 * The menu both consoles open from their floating button: the pointer modes (a long press on the
 * mouse shows or hides the mouse buttons), the keyboards (a long press moves the keyboard above or
 * below), the console's own rows, then the items -- full screen, the external display and USB
 * passthrough answered here, the rest by the console.
 */
public final class ConsoleMenu {
    /** What the menu asks of its console. */
    public interface Host {
        void selectInputMode(@NonNull InputMode mode);

        @NonNull
        KeyboardMode keyboardMode();

        void selectKeyboardMode(@NonNull KeyboardMode mode);

        void toggleFullscreen();

        /** This console's screen, as the external display is given it. */
        @NonNull
        VmOutputRouter.Screen outputScreen();

        /** An item of the console's own; false when it is not one. */
        default boolean onItem(@NonNull MenuItem item) {
            return false;
        }
    }

    private final Activity activity;
    private final View anchor;
    @MenuRes
    private final int menuRes;
    private final ConsoleInput input;
    private final ConsoleWorkspace workspace;
    private final Host host;

    public ConsoleMenu(@NonNull Activity activity, @NonNull View anchor, @MenuRes int menuRes,
                       @NonNull ConsoleInput input, @NonNull ConsoleWorkspace workspace,
                       @NonNull Host host) {
        this.activity = activity;
        this.anchor = anchor;
        this.menuRes = menuRes;
        this.input = input;
        this.workspace = workspace;
        this.host = host;
    }

    /** Opens the menu, [rows] under the keyboards. */
    public void show(@NonNull View... rows) {
        var popup = new MaterialMenu(activity, anchor);
        popup.inflate(menuRes);
        var inflater = activity.getLayoutInflater();
        var header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.addView(InputModeToggle.build(inflater, input.mode(), host::selectInputMode,
            popup::dismiss, workspace::togglePointerButtonsAndSay));
        View keyboardRow = DisplayKeyboardMenuRow.build(inflater, host.keyboardMode(),
            host::selectKeyboardMode, popup::dismiss, workspace::toggleKeyboardOnTopAndSay);
        // A row below takes the header's bottom margin, so the rows sit as evenly apart as the
        // first two.
        if (rows.length > 0)
            keyboardRow.setPaddingRelative(keyboardRow.getPaddingStart(),
                keyboardRow.getPaddingTop(), keyboardRow.getPaddingEnd(),
                Math.round(2 * activity.getResources().getDisplayMetrics().density));
        header.addView(keyboardRow);
        for (var row : rows) header.addView(row);
        popup.setHeaderView(header);
        var screen = host.outputScreen();
        ExternalOutputControl.titleMenuItem(popup.getMenu(), R.id.menu_external_output,
            screen.key());
        popup.setOnMenuItemClickListener(this::onItem);
        popup.show();
    }

    private boolean onItem(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_fullscreen) {
            host.toggleFullscreen();
            return true;
        }
        if (id == R.id.menu_external_output) {
            var screen = host.outputScreen();
            ExternalOutputControl.toggle(activity, screen.key(), host::outputScreen);
            return true;
        }
        if (id == R.id.menu_usb_passthrough) {
            var screen = host.outputScreen();
            UsbPassthroughDialog.show(activity, screen.vmId, screen.vmName);
            return true;
        }
        return host.onItem(item);
    }
}
