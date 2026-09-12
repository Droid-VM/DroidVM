// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.content.DialogInterface.BUTTON_POSITIVE;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;

/**
 * Where a device goes: the host, the sink, or one VM's controller. A rule says it about whatever
 * it matches, and the management page says it about one device in hand.
 *
 * <p>One row per VM and controller rather than a VM row with a controller question after it: the
 * two together are the target, and a VM with two controllers is two different answers. Every
 * target is offered in every layer, the catch-all one included: it is the layer where "anything
 * I have not spoken for" belongs, and all three answers to that are worth writing down.</p>
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
    public static void pick(@NonNull Context context, @NonNull List<VmEntry> vms,
                            @NonNull Map<String, List<String>> controllers,
                            @Nullable UsbDeviceTarget current, @NonNull OnPicked onPicked) {
        var targets = new ArrayList<UsbDeviceTarget>();
        var labels = new ArrayList<String>();
        var icons = new ArrayList<Integer>();
        // The controllers first: sending a device somewhere is the reason a rule is written at
        // all. Then the VM this app cannot see, typed in, because it is one of those answers
        // too. The two that send it nowhere end the list, ordered by how much they take away.
        for (var vm : vms) addVm(context, vm, controllers.get(vm.id), targets, labels, icons);
        var customAt = targets.size();
        labels.add(context.getString(R.string.usb_rules_target_custom));
        icons.add(R.drawable.ic_edit);
        targets.add(null);
        targets.add(UsbDeviceTarget.sink());
        labels.add(context.getString(R.string.usb_target_idle));
        icons.add(R.drawable.ic_sleep);
        targets.add(UsbDeviceTarget.host());
        labels.add(context.getString(R.string.usb_target_host));
        icons.add(R.drawable.ic_android);

        var view = LayoutInflater.from(context).inflate(R.layout.dialog_usb_target_edit, null);
        DropdownRowWidget dd = view.findViewById(R.id.dd_edit_target);
        var chosen = new UsbDeviceTarget[]{current};
        var dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.usb_rules_target_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                if (chosen[0] != null) onPicked.onPicked(chosen[0]);
            })
            .show();
        Runnable render = () -> {
            dd.setText(chosen[0] == null ? "" : labelOf(context, chosen[0], vms));
            var ok = dialog.getButton(BUTTON_POSITIVE);
            // A rule with no target is not a rule; the answer is refused here rather than
            // written and explained afterwards.
            if (ok != null) ok.setEnabled(chosen[0] != null);
        };
        dd.setAdapter(IconItemAdapter.create(context, labels, unbox(icons)));
        dd.setOnItemClickListener((parent, v, which, id) -> {
            if (which == customAt) {
                // The list wrote its own row into the field on the way here; the value is
                // whatever the prompt answers, or what was there before if it is dismissed.
                render.run();
                askCustom(context, target -> {
                    chosen[0] = target;
                    render.run();
                });
                return;
            }
            chosen[0] = targets.get(which);
            render.run();
        });
        render.run();
    }

    /** The int[] IconItemAdapter wants, from the list the rows were built into. */
    @NonNull
    private static int[] unbox(@NonNull List<Integer> icons) {
        var out = new int[icons.size()];
        for (var i = 0; i < out.length; i++) out[i] = icons.get(i);
        return out;
    }

    /** What the field says for a target: the row it stands for, in the same words. */
    @NonNull
    private static String labelOf(@NonNull Context context, @NonNull UsbDeviceTarget target,
                                  @NonNull List<VmEntry> vms) {
        if (target.kind == UsbRules.Target.HOST)
            return context.getString(R.string.usb_target_host);
        if (target.kind == UsbRules.Target.SINK)
            return context.getString(R.string.usb_target_idle);
        var name = target.vmId == null ? "" : target.vmId;
        for (var vm : vms)
            if (vm.id.equals(target.vmId)) name = vm.name;
        return target.controller == null
            ? context.getString(R.string.usb_rules_no_controller, name)
            : context.getString(R.string.usb_rules_vm_label_fmt, name, target.controller);
    }

    /**
     * Where one device the management page lists should go now: the xHCI controllers of the VMs
     * that are running, then the host, then nobody.
     *
     * <p>Nothing is typed in here, and nothing that cannot be done is offered: a VM that is not
     * running has no crosvm to hand a device to, and one with no controller has nothing to hand
     * it to, so neither is a row. The other two rows carry the lock in their name, because that
     * is the whole difference between choosing one of them and the device merely being there --
     * a locked device is one no rules pass will move again until it is unplugged.</p>
     */
    public static void pickForDevice(@NonNull Context context, @NonNull List<VmEntry> vms,
                                     @NonNull Map<String, List<String>> controllers,
                                     @NonNull OnPicked onPicked) {
        var targets = new ArrayList<UsbDeviceTarget>();
        var labels = new ArrayList<String>();
        for (var vm : vms) {
            if (!vm.isRunning()) continue;
            var ids = controllers.get(vm.id);
            if (ids == null) continue;
            for (var controller : ids) {
                targets.add(UsbDeviceTarget.vm(vm.id, controller));
                labels.add(context.getString(R.string.usb_rules_vm_label_fmt,
                    vm.name, controller));
            }
        }
        targets.add(UsbDeviceTarget.host());
        labels.add(lockedLabel(context, R.string.usb_target_host));
        targets.add(UsbDeviceTarget.sink());
        labels.add(lockedLabel(context, R.string.usb_target_idle));
        show(context, targets, labels, null, onPicked);
    }

    /**
     * The name of a menu row that locks the device, told from the plain word for the state the
     * device is already in: the button shows the plain word for a state nobody chose.
     */
    @NonNull
    public static String lockedLabel(@NonNull Context context, @StringRes int label) {
        return context.getString(R.string.usb_devices_locked_fmt, context.getString(label));
    }

    /** The list itself; [onCustom] runs for the one row that stands for no target. */
    private static void show(@NonNull Context context, @NonNull List<UsbDeviceTarget> targets,
                             @NonNull List<String> labels, @Nullable Runnable onCustom,
                             @NonNull OnPicked onPicked) {
        new MaterialAlertDialogBuilder(context)
            // The management page's own list, which asks where a device goes now rather than
            // editing a rule that will decide it later.
            .setTitle(R.string.usb_rules_attach_to)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                if (which < targets.size()) onPicked.onPicked(targets.get(which));
                else if (onCustom != null) onCustom.run();
            })
            .show();
    }

    private static void addVm(@NonNull Context context, @NonNull VmEntry vm,
                              @Nullable List<String> controllers,
                              @NonNull List<UsbDeviceTarget> targets,
                              @NonNull List<String> labels, @Nullable List<Integer> icons) {
        // The name alone, not the state beside it: a rule is written for whenever it matches,
        // and whether the VM happens to be stopped while somebody types it says nothing about
        // where the device should go when it is not.
        var name = vm.name;
        if (controllers == null || controllers.isEmpty()) {
            targets.add(UsbDeviceTarget.vm(vm.id, null));
            labels.add(context.getString(R.string.usb_rules_no_controller, name));
            if (icons != null) icons.add(R.drawable.ic_nav_vm);
            return;
        }
        for (var controller : controllers) {
            targets.add(UsbDeviceTarget.vm(vm.id, controller));
            labels.add(context.getString(R.string.usb_rules_vm_label_fmt, name, controller));
            if (icons != null) icons.add(R.drawable.ic_nav_vm);
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
