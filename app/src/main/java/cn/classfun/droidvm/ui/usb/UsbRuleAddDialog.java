// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;

/**
 * Adds one rule to a layer. Nothing is typed: the device or port comes from what is plugged in
 * right now, the target from the VM list plus "keep on host" where the layer allows it. The
 * rule is handed back in the wire shape, ready to sit in the layer's adapter.
 */
public final class UsbRuleAddDialog {
    /** A row of the device/port dropdown: the label shown and the id/port it stands for. */
    private static final class Subject {
        final String label;
        final String id;
        final String port;

        Subject(@NonNull String label, @NonNull String id, @NonNull String port) {
            this.label = label;
            this.id = id;
            this.port = port;
        }
    }

    /** A row of the target dropdown; a null vm is "keep on host". */
    private static final class Target {
        final String label;
        @Nullable
        final String vm;

        Target(@NonNull String label, @Nullable String vm) {
            this.label = label;
            this.vm = vm;
        }
    }

    private final Context context;
    private final UsbRuleLayer layer;
    private final List<Subject> subjects;
    private final List<Target> targets;
    private final Consumer<DataItem> onAdd;
    private int subjectIndex = -1;
    private int targetIndex = -1;
    private AlertDialog dialog;

    private UsbRuleAddDialog(@NonNull Context context, @NonNull UsbRuleLayer layer,
                             @NonNull List<UsbHostDeviceInfo> devices,
                             @NonNull List<VmEntry> vms, @NonNull Consumer<DataItem> onAdd) {
        this.context = context;
        this.layer = layer;
        this.subjects = buildSubjects(context, layer, devices);
        this.targets = buildTargets(context, layer, vms);
        this.onAdd = onAdd;
    }

    public static void show(@NonNull Context context, @NonNull UsbRuleLayer layer,
                            @NonNull List<UsbHostDeviceInfo> devices,
                            @NonNull List<VmEntry> vms, @NonNull Consumer<DataItem> onAdd) {
        new UsbRuleAddDialog(context, layer, devices, vms, onAdd).show();
    }

    private void show() {
        var view = LayoutInflater.from(context).inflate(R.layout.dialog_usb_rule_add, null);
        TextView hint = view.findViewById(R.id.tv_add_hint);
        DropdownRowWidget ddDevice = view.findViewById(R.id.dd_add_device);
        DropdownRowWidget ddPort = view.findViewById(R.id.dd_add_port);
        DropdownRowWidget ddTarget = view.findViewById(R.id.dd_add_target);
        hint.setText(hintRes());
        ddDevice.setVisibility(GONE);
        ddPort.setVisibility(GONE);
        if (layer.needsSubject()) {
            var row = layer == UsbRuleLayer.PORT ? ddPort : ddDevice;
            row.setVisibility(VISIBLE);
            row.setAdapter(new ArrayAdapter<>(
                context, android.R.layout.simple_list_item_1, subjectLabels()));
            row.setOnItemClickListener((p, v, pos, id) -> {
                subjectIndex = pos;
                updateOk();
            });
            if (subjects.isEmpty()) {
                hint.setText(R.string.usb_rules_pick_no_devices);
                row.setEnabled(false);
            } else {
                // One thing plugged in is the common case; offer it, still changeable.
                subjectIndex = 0;
                row.setText(subjects.get(0).label);
            }
        }
        ddTarget.setAdapter(new ArrayAdapter<>(
            context, android.R.layout.simple_list_item_1, targetLabels()));
        ddTarget.setOnItemClickListener((p, v, pos, id) -> {
            targetIndex = pos;
            updateOk();
        });
        if (targets.isEmpty()) {
            hint.setText(R.string.usb_rules_pick_no_vms);
            ddTarget.setEnabled(false);
        }
        dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(layer.titleRes)
            .setView(view)
            .setPositiveButton(R.string.usb_rules_add, (d, w) -> commit())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        updateOk();
    }

    private void updateOk() {
        if (dialog == null) return;
        var ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (ok == null) return;
        boolean subjectOk = !layer.needsSubject() || subjectIndex >= 0;
        ok.setEnabled(subjectOk && targetIndex >= 0);
    }

    private void commit() {
        if (targetIndex < 0 || targetIndex >= targets.size()) return;
        var rule = DataItem.newObject();
        if (layer.needsSubject()) {
            if (subjectIndex < 0 || subjectIndex >= subjects.size()) return;
            var subject = subjects.get(subjectIndex);
            if (layer.hasId) rule.set("id", subject.id);
            if (layer.hasPort) rule.set("port", subject.port);
        }
        rule.set("vm", targets.get(targetIndex).vm);
        onAdd.accept(rule);
    }

    @StringRes
    private int hintRes() {
        switch (layer) {
            case EXACT:
                return R.string.usb_rules_pick_hint_exact;
            case PORT:
                return R.string.usb_rules_pick_hint_port;
            case DEVICE:
                return R.string.usb_rules_pick_hint_device;
            default:
                return R.string.usb_rules_pick_hint_any;
        }
    }

    @NonNull
    private String[] subjectLabels() {
        var labels = new String[subjects.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = subjects.get(i).label;
        return labels;
    }

    @NonNull
    private String[] targetLabels() {
        var labels = new String[targets.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = targets.get(i).label;
        return labels;
    }

    /**
     * One row per plugged-in device for the exact and device layers (the device layer folds
     * two identical serial-less devices into one, as the rule would), one row per occupied
     * port for the port layer. Hubs never arrive: the daemon leaves them out of the list.
     */
    @NonNull
    private static List<Subject> buildSubjects(@NonNull Context context,
                                               @NonNull UsbRuleLayer layer,
                                               @NonNull List<UsbHostDeviceInfo> devices) {
        var out = new LinkedHashMap<String, Subject>();
        for (var device : devices) {
            var name = device.displayName(context);
            switch (layer) {
                case EXACT:
                    out.putIfAbsent(fmt("%s@%s", device.id, device.port), new Subject(
                        context.getString(R.string.usb_rules_pick_device_exact_fmt,
                            name, device.id, device.port),
                        device.id, device.port));
                    break;
                case PORT:
                    out.putIfAbsent(device.port, new Subject(
                        context.getString(R.string.usb_rules_pick_device_port_fmt,
                            device.port, name),
                        "", device.port));
                    break;
                case DEVICE:
                    out.putIfAbsent(device.id, new Subject(
                        context.getString(R.string.usb_rules_pick_device_device_fmt,
                            name, device.id),
                        device.id, ""));
                    break;
                default:
                    break;
            }
        }
        return new ArrayList<>(out.values());
    }

    @NonNull
    private static List<Target> buildTargets(@NonNull Context context,
                                             @NonNull UsbRuleLayer layer,
                                             @NonNull List<VmEntry> vms) {
        var out = new ArrayList<Target>();
        if (layer.allowsHost())
            out.add(new Target(context.getString(R.string.usb_rules_keep_host), null));
        for (var vm : vms) out.add(new Target(vm.label(context), vm.id));
        return out;
    }
}
