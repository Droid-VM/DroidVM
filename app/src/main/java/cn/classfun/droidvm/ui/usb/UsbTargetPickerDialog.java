// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.content.DialogInterface.BUTTON_POSITIVE;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.R;

/**
 * Where a device goes: the host, the sink, or one VM's controller. A rule says it about whatever
 * it matches, and the management page says it about one device in hand.
 *
 * <p>One row per VM and controller rather than a VM row with a controller question after it: the
 * two together are the target, and a VM with two controllers is two different answers. The sink
 * is offered in every layer, the catch-all one included -- it is the layer where "anything I have
 * not spoken for disappears" belongs, and the only one where keeping the device on the host is
 * refused, because nothing follows it.</p>
 */
public final class UsbTargetPickerDialog {
    public interface OnPicked {
        void onPicked(@NonNull UsbDeviceTarget target);
    }

    private UsbTargetPickerDialog() {
    }

    /**
     * @param controllers each VM's xHCI controller ids, as vms.json has them. A VM with none is
     *                    still offered, as the row that names no controller: that is what every
     *                    rule written before controllers existed says, and the card calls it out
     *                    in red rather than the picker hiding a VM the user can see.
     */
    public static void pick(@NonNull Context context, @NonNull UsbRuleLayer layer,
                            @NonNull List<VmEntry> vms,
                            @NonNull Map<String, List<String>> controllers,
                            @NonNull OnPicked onPicked) {
        var targets = new ArrayList<UsbDeviceTarget>();
        var labels = new ArrayList<String>();
        if (layer.allowsHost()) {
            targets.add(UsbDeviceTarget.host());
            labels.add(context.getString(R.string.usb_rules_keep_host));
        }
        targets.add(UsbDeviceTarget.sink());
        labels.add(context.getString(R.string.usb_rules_target_sink_pick));
        for (var vm : vms) addVm(context, vm, controllers.get(vm.id), targets, labels);
        labels.add(context.getString(R.string.usb_rules_target_custom));
        show(context, targets, labels, () -> askCustom(context, onPicked), onPicked);
    }

    /**
     * Where one device the management page lists should go now.
     *
     * <p>Nothing is typed in here: the page lists what is plugged in, and a VM with no controller
     * has nothing to attach to, so it is left out rather than offered and refused. A VM that is
     * not running is offered, with its state in the label -- the daemon answers that it is not
     * running and the page says so, which is truer than hiding the option.</p>
     */
    public static void pickForDevice(@NonNull Context context, @NonNull List<VmEntry> vms,
                                     @NonNull Map<String, List<String>> controllers,
                                     @NonNull OnPicked onPicked) {
        var targets = new ArrayList<UsbDeviceTarget>();
        var labels = new ArrayList<String>();
        targets.add(UsbDeviceTarget.host());
        labels.add(context.getString(R.string.usb_devices_target_host));
        for (var vm : vms) {
            var ids = controllers.get(vm.id);
            if (ids == null) continue;
            for (var controller : ids) {
                targets.add(UsbDeviceTarget.vm(vm.id, controller));
                labels.add(context.getString(R.string.usb_rules_vm_label_fmt,
                    vm.label(context), controller));
            }
        }
        targets.add(UsbDeviceTarget.sink());
        labels.add(context.getString(R.string.usb_rules_target_sink_pick));
        show(context, targets, labels, null, onPicked);
    }

    /** The list itself; [onCustom] runs for the one row that stands for no target. */
    private static void show(@NonNull Context context, @NonNull List<UsbDeviceTarget> targets,
                             @NonNull List<String> labels, @Nullable Runnable onCustom,
                             @NonNull OnPicked onPicked) {
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.usb_rules_field_target)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                if (which < targets.size()) onPicked.onPicked(targets.get(which));
                else if (onCustom != null) onCustom.run();
            })
            .show();
    }

    private static void addVm(@NonNull Context context, @NonNull VmEntry vm,
                              @Nullable List<String> controllers,
                              @NonNull List<UsbDeviceTarget> targets,
                              @NonNull List<String> labels) {
        var name = vm.label(context);
        if (controllers == null || controllers.isEmpty()) {
            targets.add(UsbDeviceTarget.vm(vm.id, null));
            labels.add(context.getString(R.string.usb_rules_no_controller, name));
            return;
        }
        for (var controller : controllers) {
            targets.add(UsbDeviceTarget.vm(vm.id, controller));
            labels.add(context.getString(R.string.usb_rules_vm_label_fmt, name, controller));
        }
    }

    /**
     * A VM this app cannot see, typed in.
     *
     * <p>Only emptiness is checked. There is no pattern in the daemon to check a VM id against
     * the way there is for a device id, and inventing one here would be a second answer to a
     * question the daemon already answers: the card renders an id it cannot resolve in red, and
     * the save is what refuses it.</p>
     */
    private static void askCustom(@NonNull Context context, @NonNull OnPicked onPicked) {
        var view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_usb_target_custom, null);
        TextInputLayout tiVm = view.findViewById(R.id.ti_custom_vm);
        TextInputEditText etVm = view.findViewById(R.id.et_custom_vm);
        TextInputEditText etController = view.findViewById(R.id.et_custom_controller);
        var dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.usb_rules_custom_target_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show();
        // Wired after show() so a refused value can stay on screen with its error: the listener
        // setPositiveButton takes dismisses the dialog whatever it decides.
        dialog.getButton(BUTTON_POSITIVE).setOnClickListener(v -> {
            var vmId = text(etVm);
            tiVm.setError(vmId.isEmpty()
                ? context.getString(R.string.usb_rules_custom_vm_required) : null);
            if (tiVm.getError() != null) return;
            var controller = text(etController);
            dialog.dismiss();
            onPicked.onPicked(
                UsbDeviceTarget.vm(vmId, controller.isEmpty() ? null : controller));
        });
    }

    @NonNull
    private static String text(@NonNull TextInputEditText field) {
        var value = field.getText();
        return value == null ? "" : value.toString().trim();
    }
}
