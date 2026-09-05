// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.R;

/**
 * Shows the daemon's dry run ({@code usb_rules_test}): for every plugged-in device, where it
 * is now and which rule would claim it. A report only -- the daemon attaches nothing for it.
 */
public final class UsbRulesPreviewDialog {
    private final Context context;
    private final List<UsbHostDeviceInfo> known;
    private final Map<String, String> vmNames;

    private UsbRulesPreviewDialog(@NonNull Context context,
                                  @NonNull List<UsbHostDeviceInfo> known,
                                  @NonNull Map<String, String> vmNames) {
        this.context = context;
        this.known = known;
        this.vmNames = vmNames;
    }

    /**
     * @param devices the dry run's {@code devices} array
     * @param known   the current host list, for product names the dry run does not repeat
     * @param vmNames VM id to name, for the targets
     */
    public static void show(@NonNull Context context, @Nullable JSONArray devices,
                            @NonNull List<UsbHostDeviceInfo> known,
                            @NonNull Map<String, String> vmNames) {
        new UsbRulesPreviewDialog(context, known, vmNames).show(devices);
    }

    private void show(@Nullable JSONArray devices) {
        var inflater = LayoutInflater.from(context);
        var view = inflater.inflate(R.layout.dialog_usb_rules_preview, null);
        ViewGroup rows = view.findViewById(R.id.preview_rows);
        TextView empty = view.findViewById(R.id.tv_preview_empty);
        int count = devices == null ? 0 : devices.length();
        empty.setVisibility(count == 0 ? VISIBLE : GONE);
        for (int i = 0; i < count; i++) {
            var obj = devices.optJSONObject(i);
            if (obj == null) continue;
            var row = inflater.inflate(R.layout.item_usb_rule_preview, rows, false);
            bindRow(row, obj);
            rows.addView(row);
        }
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.usb_rules_preview)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void bindRow(@NonNull View row, @NonNull JSONObject obj) {
        var sysfs = obj.optString("sysfs", "");
        var id = obj.optString("id", "");
        var port = obj.optString("port", "");
        TextView tvName = row.findViewById(R.id.tv_preview_name);
        TextView tvId = row.findViewById(R.id.tv_preview_id);
        TextView tvState = row.findViewById(R.id.tv_preview_state);
        TextView tvResult = row.findViewById(R.id.tv_preview_result);
        tvName.setText(deviceName(sysfs, id));
        tvId.setText(context.getString(R.string.usb_rules_desc_exact, id, port));
        tvState.setText(stateText(obj));
        tvResult.setText(resultText(obj.optJSONObject("result")));
    }

    @NonNull
    private String deviceName(@NonNull String sysfs, @NonNull String id) {
        for (var device : known)
            if (!sysfs.isEmpty() && device.sysfs.equals(sysfs)) return device.displayName(context);
        for (var device : known)
            if (!id.isEmpty() && device.id.equals(id)) return device.displayName(context);
        return context.getString(R.string.usb_rules_unknown_device, id.isEmpty() ? sysfs : id);
    }

    /** Attached to a VM, held back by a manual detach, or free on the host. */
    @NonNull
    private String stateText(@NonNull JSONObject obj) {
        if (!obj.isNull("attached_vm")) {
            var vm = obj.optString("attached_vm", "");
            var fallback = obj.isNull("attached_vm_name") ? "" : obj.optString("attached_vm_name", "");
            return context.getString(R.string.usb_rules_state_attached, vmName(vm, fallback));
        }
        if (obj.optBoolean("held", false))
            return context.getString(R.string.usb_rules_state_held);
        return context.getString(R.string.usb_rules_state_host);
    }

    @NonNull
    private String vmName(@NonNull String vm, @NonNull String fallback) {
        var name = vmNames.get(vm);
        if (name != null) return name;
        if (!fallback.isEmpty()) return fallback;
        return context.getString(R.string.usb_rules_unknown_vm, vm);
    }

    /**
     * Which rule would win. The daemon's index is the rule's position in its list, shown
     * 1-based to match the numbering on the page.
     */
    @NonNull
    private String resultText(@Nullable JSONObject result) {
        if (result == null) return context.getString(R.string.usb_rules_result_none);
        var layer = UsbRuleLayer.fromValue(result.opt("layer"));
        int layerNo = layer == null ? result.optInt("layer", 0) : layer.number();
        int ruleNo = result.optInt("index", -1) + 1;
        if (result.isNull("vm"))
            return context.getString(R.string.usb_rules_result_host, layerNo, ruleNo);
        var vm = result.optString("vm", "");
        return context.getString(R.string.usb_rules_result_vm, layerNo, ruleNo, vmName(vm, ""));
    }
}
