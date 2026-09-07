// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.content.DialogInterface.BUTTON_POSITIVE;
import static android.view.View.GONE;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;

/**
 * What one rule matches on, asked as one dialog instead of a field at a time.
 *
 * <p>A rule's matcher is one thought -- "this device", "that socket", "that device in that
 * socket" -- and the page used to ask for it a button at a time, which made an exact rule two
 * questions that could disagree. Here the exact zone's two dropdowns are one: picking a
 * plugged-in device from either fills BOTH, because a device that is plugged in is in exactly
 * one socket and there is nothing to choose between. Only "Custom" breaks the pair, and that is
 * the whole reason it exists -- typing a value in is how someone writes a rule about a device
 * that is not here, or about a socket whatever is in it.</p>
 *
 * <p>The catch-all zone matches on everything, so it has nothing to ask: its dialog is the title
 * and the buttons, which is still worth showing because deleting the rule has to live
 * somewhere.</p>
 */
public final class UsbRuleEditDialog {
    /** What the dialog answers with. [onDelete] is null for a rule that does not exist yet. */
    public interface Listener {
        void onConfirm(@Nullable String id, @Nullable String port);

        void onDelete();
    }

    private final Context context;
    private final UsbRuleLayer layer;
    private final List<UsbHostDeviceInfo> devices;
    private final Listener listener;
    /** The values as the dialog has them now; written back only when it is confirmed. */
    private String id;
    private String port;
    private DropdownRowWidget ddId;
    private DropdownRowWidget ddPort;
    private androidx.appcompat.app.AlertDialog dialog;

    private UsbRuleEditDialog(@NonNull Context context, @NonNull UsbRuleLayer layer,
                              @NonNull List<UsbHostDeviceInfo> devices, @Nullable String id,
                              @Nullable String port, @NonNull Listener listener) {
        this.context = context;
        this.layer = layer;
        this.devices = devices;
        this.id = id == null ? "" : id;
        this.port = port == null ? "" : port;
        this.listener = listener;
    }

    /**
     * Opens the dialog for an existing rule: the title says edit, and Delete is offered because
     * there is something to delete.
     */
    public static void edit(@NonNull Context context, @NonNull UsbRuleLayer layer,
                            @NonNull List<UsbHostDeviceInfo> devices, @Nullable String id,
                            @Nullable String port, @NonNull Listener listener) {
        new UsbRuleEditDialog(context, layer, devices, id, port, listener).show(false);
    }

    /**
     * The same dialog for a rule being written. Delete is left out rather than shown and made to
     * mean cancel: nothing has been added yet, so there is nothing it could take away.
     */
    public static void add(@NonNull Context context, @NonNull UsbRuleLayer layer,
                           @NonNull List<UsbHostDeviceInfo> devices,
                           @NonNull Listener listener) {
        new UsbRuleEditDialog(context, layer, devices, null, null, listener).show(true);
    }

    private void show(boolean adding) {
        var builder = new MaterialAlertDialogBuilder(context)
            .setTitle(adding ? R.string.usb_rules_add_title : R.string.usb_rules_edit_title);
        if (layer.needsSubject()) {
            var view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_usb_rule_edit, null);
            ddId = view.findViewById(R.id.dd_edit_id);
            ddPort = view.findViewById(R.id.dd_edit_port);
            if (!layer.hasId) ddId.setVisibility(GONE);
            if (!layer.hasPort) ddPort.setVisibility(GONE);
            if (layer.hasId) fill(ddId, true);
            if (layer.hasPort) fill(ddPort, false);
            builder.setView(view);
        }
        // The catch-all zone's dialog has nothing to cancel, so it is not offered one: the two
        // buttons it has are the two things anyone can do to a rule that says "everything".
        if (layer.needsSubject() || adding)
            builder.setNegativeButton(android.R.string.cancel, null);
        if (!adding)
            builder.setNeutralButton(R.string.usb_rules_delete_action,
                (d, w) -> listener.onDelete());
        builder.setPositiveButton(android.R.string.ok,
            (d, w) -> listener.onConfirm(layer.hasId ? id : null, layer.hasPort ? port : null));
        dialog = builder.show();
        // Before the window animates away rather than after it is gone: a popup is its own
        // window, and one still open while its dialog fades is a window the compositor is left
        // holding with nothing to anchor it. Cancelling by touching outside is the path that
        // shows it, because that dismissal starts on the DOWN rather than on a button.
        dialog.setOnDismissListener(d -> {
            if (ddId != null) ddId.dismissPopup();
            if (ddPort != null) ddPort.dismissPopup();
        });
        render();
    }

    /**
     * Both fields and the confirm, from the values as they stand. The field shows the value the
     * rule stores rather than the row that was picked: that is what is saved, what the card
     * prints, and the only thing there is to show for a value typed in by hand.
     */
    private void render() {
        if (ddId != null) ddId.setText(id);
        if (ddPort != null) ddPort.setText(port);
        var ok = dialog.getButton(BUTTON_POSITIVE);
        // A rule saved without the field its zone matches on would match nothing and could not
        // be told from one that matches everything, so the answer is refused here rather than
        // written and explained afterwards.
        if (ok != null) ok.setEnabled(complete());
    }

    private boolean complete() {
        if (layer.hasId && id.isEmpty()) return false;
        return !layer.hasPort || !port.isEmpty();
    }

    /**
     * One dropdown. The rows are the devices plugged in now, listed the way the field asks about
     * them, and picking one sets BOTH fields -- see the class comment. Custom is the last row
     * and sets only the field it was opened from.
     */
    private void fill(@NonNull DropdownRowWidget widget, boolean wantsId) {
        var subjects = UsbRuleSubjects.of(context,
            wantsId ? UsbRuleLayer.DEVICE : UsbRuleLayer.PORT, devices);
        var labels = new ArrayList<String>(subjects.size() + 1);
        var icons = new int[subjects.size() + 1];
        for (var subject : subjects) {
            icons[labels.size()] = wantsId ? R.drawable.ic_usb : R.drawable.ic_connection;
            labels.add(subject.label);
        }
        icons[labels.size()] = R.drawable.ic_edit;
        labels.add(context.getString(R.string.edit_vm_xhci_add_custom));
        widget.setAdapter(IconItemAdapter.create(context, labels, icons));
        widget.setOnItemClickListener((parent, view, which, rowId) -> {
            if (which >= subjects.size()) {
                // The list wrote its own row into the field on the way here; the value is
                // whatever the prompt answers, or what was there before if it is dismissed.
                render();
                askCustom(wantsId);
                return;
            }
            // The whole device, not the field that was asked about: the subject list for one
            // field carries only that field, so the other is read off the device again.
            var subject = subjects.get(which);
            var device = UsbDeviceNames.deviceFor(
                wantsId ? UsbRuleLayer.DEVICE : UsbRuleLayer.PORT,
                subject.id, subject.port, devices);
            if (device != null) {
                id = device.id;
                port = device.port;
            } else if (wantsId) {
                id = subject.id;
            } else {
                port = subject.port;
            }
            render();
        });
    }

    /**
     * The typed-in value for one field, checked against the pattern the daemon checks. Only that
     * field is written: a device that is not plugged in has no port to read off it, and guessing
     * one is how a rule ends up about a socket nobody chose.
     */
    private void askCustom(boolean wantsId) {
        var view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_xhci_custom_binding, null);
        TextInputLayout tiId = view.findViewById(R.id.ti_custom_id);
        TextInputLayout tiPort = view.findViewById(R.id.ti_custom_port);
        TextInputEditText etId = view.findViewById(R.id.et_custom_id);
        TextInputEditText etPort = view.findViewById(R.id.et_custom_port);
        (wantsId ? tiPort : tiId).setVisibility(GONE);
        (wantsId ? etId : etPort).setText(wantsId ? id : port);
        var custom = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_vm_xhci_custom_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show();
        // Wired after show() so a refused value can stay on screen with its error: the listener
        // setPositiveButton takes dismisses the dialog whatever it decides.
        custom.getButton(BUTTON_POSITIVE).setOnClickListener(v -> {
            var field = wantsId ? tiId : tiPort;
            var value = text(wantsId ? etId : etPort);
            var pattern = wantsId ? UsbRules.ID_PATTERN : UsbRules.PORT_PATTERN;
            field.setError(pattern.matcher(value).matches() ? null : context.getString(wantsId
                ? R.string.edit_vm_xhci_custom_bad_id : R.string.edit_vm_xhci_custom_bad_port));
            if (field.getError() != null) return;
            custom.dismiss();
            if (wantsId) id = value;
            else port = value;
            render();
        });
    }

    @NonNull
    private static String text(@NonNull TextInputEditText field) {
        var value = field.getText();
        return value == null ? "" : value.toString().trim();
    }
}
