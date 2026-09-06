// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import static android.content.DialogInterface.BUTTON_POSITIVE;
import static android.view.View.GONE;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.Menu;
import android.widget.PopupMenu;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.ui.usb.UsbHostDeviceInfo;
import cn.classfun.droidvm.ui.usb.UsbRuleLayer;
import cn.classfun.droidvm.ui.usb.UsbRuleSubjects;

/**
 * Adding a binding to an xHCI card: which layer, then what in that layer.
 *
 * <p>The layer is asked first because it decides what the answer even is -- an exact rule is
 * about a device on a port, a port rule about the port alone -- and only the part its layer
 * stores is taken from the device that was picked. That is the same folding the global rules
 * page does, through the same {@link UsbRuleSubjects} list, so a device reads the same in both.
 * The catch-all layer has nothing to ask about and is added straight away.</p>
 */
final class XhciAddBindingDialog {
    /** Called with the layer to add to and the fields that layer stores; the rest stay null. */
    interface OnPicked {
        void onPicked(@NonNull UsbRuleLayer layer, @Nullable String id, @Nullable String port);
    }

    private XhciAddBindingDialog() {
    }

    /**
     * The "+ Add device" menu.
     *
     * @param anyTaken whether the catch-all zone already has its row. A second one would be a
     *                 rule nothing can ever reach, so the entry is left out rather than shown
     *                 and refused -- the same answer the sound card gives a full direction.
     */
    static void show(@NonNull Context context, @NonNull List<UsbHostDeviceInfo> devices,
                     boolean anyTaken, @NonNull OnPicked onPicked) {
        var menu = new PopupMenu(context, null).getMenu();
        for (var layer : UsbRuleLayer.values()) {
            if (layer == UsbRuleLayer.ANY && anyTaken) continue;
            var item = menu.add(Menu.NONE, layer.ordinal(), layer.ordinal(),
                context.getString(layer.titleRes));
            item.setIcon(iconOf(layer));
        }
        new MenuDialogBuilder(context)
            .setTitle(R.string.edit_vm_xhci_device_add)
            .setMenu(menu)
            .setListener(item -> {
                var layer = UsbRuleLayer.values()[item.getItemId()];
                if (layer.needsSubject()) pick(context, layer, devices, onPicked);
                else onPicked.onPicked(layer, null, null);
                return true;
            })
            .show();
    }

    /**
     * What one layer's rule is about: the devices plugged in right now, then "Custom...".
     *
     * <p>Custom is last rather than a menu entry of its own, so it inherits the layer that was
     * already chosen -- the resolution dropdown's precedent. Also the picker a row's value
     * button reopens: replacing what a rule points at is the same question as choosing it.</p>
     */
    static void pick(@NonNull Context context, @NonNull UsbRuleLayer layer,
                     @NonNull List<UsbHostDeviceInfo> devices, @NonNull OnPicked onPicked) {
        var subjects = UsbRuleSubjects.of(context, layer, devices);
        var labels = new ArrayList<String>(subjects.size() + 1);
        for (var subject : subjects) labels.add(subject.label);
        labels.add(context.getString(R.string.edit_vm_xhci_add_custom));
        new MaterialAlertDialogBuilder(context)
            .setTitle(layer.titleRes)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                if (which >= subjects.size()) {
                    askCustom(context, layer, onPicked);
                    return;
                }
                var subject = subjects.get(which);
                onPicked.onPicked(layer,
                    layer.hasId ? subject.id : null, layer.hasPort ? subject.port : null);
            })
            .show();
    }

    /**
     * The typed-in value, for a device that is not plugged in right now.
     *
     * <p>Checked against the patterns the daemon checks, so a value that gets past here is one
     * the rules save will take: the save writes the VM config first, and being told about a typo
     * only after that has landed is later than it needs to be.</p>
     */
    private static void askCustom(@NonNull Context context, @NonNull UsbRuleLayer layer,
                                  @NonNull OnPicked onPicked) {
        var view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_xhci_custom_binding, null);
        TextInputLayout tiId = view.findViewById(R.id.ti_custom_id);
        TextInputLayout tiPort = view.findViewById(R.id.ti_custom_port);
        TextInputEditText etId = view.findViewById(R.id.et_custom_id);
        TextInputEditText etPort = view.findViewById(R.id.et_custom_port);
        if (!layer.hasId) tiId.setVisibility(GONE);
        if (!layer.hasPort) tiPort.setVisibility(GONE);
        var dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_vm_xhci_custom_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show();
        // Wired after show() so a refused value can stay on screen with its error: the listener
        // setPositiveButton takes dismisses the dialog whatever it decides.
        dialog.getButton(BUTTON_POSITIVE).setOnClickListener(v -> {
            String id = null;
            String port = null;
            if (layer.hasId) {
                id = text(etId);
                tiId.setError(UsbRules.ID_PATTERN.matcher(id).matches()
                    ? null : context.getString(R.string.edit_vm_xhci_custom_bad_id));
                if (tiId.getError() != null) return;
            }
            if (layer.hasPort) {
                port = text(etPort);
                tiPort.setError(UsbRules.PORT_PATTERN.matcher(port).matches()
                    ? null : context.getString(R.string.edit_vm_xhci_custom_bad_port));
                if (tiPort.getError() != null) return;
            }
            dialog.dismiss();
            onPicked.onPicked(layer, id, port);
        });
    }

    /** The same icons the global page's add dialog puts on the same three questions. */
    @DrawableRes
    private static int iconOf(@NonNull UsbRuleLayer layer) {
        if (layer == UsbRuleLayer.PORT) return R.drawable.ic_connection;
        if (layer == UsbRuleLayer.ANY) return R.drawable.ic_nav_vm;
        return R.drawable.ic_usb;
    }

    @NonNull
    private static String text(@NonNull TextInputEditText field) {
        var value = field.getText();
        return value == null ? "" : value.toString().trim();
    }
}
