// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import android.content.Context;
import android.view.Menu;
import android.widget.PopupMenu;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;

/**
 * Which zone a new rule goes in, for the one surface that has no button per zone.
 *
 * <p>The rules page asks nothing here: its zones are four lists with four add buttons, so the
 * zone is already answered by which button was pressed. A VM's xHCI card is one list of bound
 * rules with one add button, so the zone has to be asked first -- and it has to be first,
 * because it decides what the next question even is: an exact rule is about a device in a
 * socket, a port rule about the socket alone, and the catch-all rule about nothing in
 * particular and so about nothing to ask.</p>
 *
 * <p>Everything after that is {@link UsbRuleEditDialog}, the same dialog the rules page opens,
 * so a rule is written the same way wherever it is written. What differs is only what happens
 * to the answer: the rules page goes on to ask where the device should go, and a card on a VM's
 * controller already knows -- there.</p>
 */
public final class UsbRuleZoneDialog {
    /** Called with the zone chosen and the fields that zone stores; the rest stay null. */
    public interface OnPicked {
        void onPicked(@NonNull UsbRuleLayer layer, @Nullable String id, @Nullable String port);
    }

    private UsbRuleZoneDialog() {
    }

    public static void show(@NonNull Context context, @NonNull List<UsbHostDeviceInfo> devices,
                            @NonNull OnPicked onPicked) {
        var menu = new PopupMenu(context, null).getMenu();
        for (var layer : UsbRuleLayer.values()) {
            var item = menu.add(Menu.NONE, layer.ordinal(), layer.ordinal(),
                context.getString(layer.titleRes));
            item.setIcon(iconOf(layer));
        }
        new MenuDialogBuilder(context)
            .setTitle(R.string.edit_vm_xhci_device_add)
            .setMenu(menu)
            .setListener(item -> {
                var layer = UsbRuleLayer.values()[item.getItemId()];
                if (!layer.needsSubject()) {
                    onPicked.onPicked(layer, null, null);
                    return true;
                }
                UsbRuleEditDialog.add(context, layer, devices, new UsbRuleEditDialog.Listener() {
                    @Override
                    public void onConfirm(@Nullable String id, @Nullable String port) {
                        onPicked.onPicked(layer, id, port);
                    }

                    @Override
                    public void onDelete() {
                    }
                });
                return true;
            })
            .show();
    }

    /** One icon per zone: a socket for a port rule, a VM for the catch-all, else a device. */
    @DrawableRes
    private static int iconOf(@NonNull UsbRuleLayer layer) {
        if (layer == UsbRuleLayer.PORT) return R.drawable.ic_connection;
        if (layer == UsbRuleLayer.ANY) return R.drawable.ic_nav_vm;
        return R.drawable.ic_usb;
    }
}
