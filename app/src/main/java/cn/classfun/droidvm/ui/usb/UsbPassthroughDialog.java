// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/**
 * The console's USB passthrough: which of the plugged-in devices this VM gets, and two that stand
 * for many -- every device, and every HID device (keyboards, mice) -- as rules of the one shared
 * rule set, so the rules page shows and edits what was chosen here and nothing is kept anywhere
 * else.
 *
 * <p>A device checked is a device-layer rule handing it to this VM; "every device" a catch-all
 * rule, "HID devices" a class rule for class 03. A rule written here goes first in its layer --
 * the user has just said this device goes to this VM -- and to the VM's first USB controller.
 * Only what the user changed is touched: a row left as it was leaves its rules where they were.
 * Unchecking removes this VM's rules for that row and no other VM's. The daemon applies the set
 * as it saves it, to what is plugged in now.</p>
 *
 * <p>In place of the VNC projection page's USB HID capture, which took a USB keyboard or mouse
 * from Android and spoke boot-protocol reports over RFB: the guest gets the device itself.</p>
 */
public final class UsbPassthroughDialog {
    private static final String TAG = "UsbPassthroughDialog";
    private static final String TARGET_VM = "vm";

    /** One row of the dialog: the layer its rule goes in and what the rule names. */
    private static final class Choice {
        final UsbRuleLayer layer;
        @Nullable
        final String id;
        @Nullable
        final String cls;
        final String label;

        Choice(@NonNull UsbRuleLayer layer, @Nullable String id, @Nullable String cls,
               @NonNull String label) {
            this.layer = layer;
            this.id = id;
            this.cls = cls;
            this.label = label;
        }

        /** Whether [rule] is this VM's rule for this row. */
        boolean isRuleFor(@NonNull JSONObject rule, @NonNull String vmId) {
            return TARGET_VM.equals(rule.optString("target", rule.has("vm") ? TARGET_VM : ""))
                && vmId.equals(rule.optString("vm", ""))
                && Objects.equals(id, rule.isNull("id") ? null : rule.optString("id", null))
                && Objects.equals(cls, rule.isNull("class") ? null : rule.optString("class", null));
        }
    }

    private UsbPassthroughDialog() {
    }

    /** Opens the dialog for [vmId], or says why it cannot do anything for it. */
    public static void show(@NonNull Activity activity, @NonNull String vmId,
                            @NonNull String vmName) {
        if (!hasUsbController(activity, vmId)) {
            new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.usb_pass_title, vmName))
                .setMessage(R.string.usb_pass_no_controller)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        var main = new Handler(Looper.getMainLooper());
        DaemonConnection.getInstance().buildRequest("usb_rules_get")
            .onResponse(rules -> DaemonConnection.getInstance().buildRequest("usb_host_list")
                .onResponse(devices -> main.post(() -> {
                    if (activity.isFinishing()) return;
                    build(activity, vmId, vmName, rules.optJSONObject("rules"),
                        UsbHostDeviceInfo.fromArray(devices.optJSONArray("devices")));
                }))
                .onUnsuccessful(r -> main.post(() -> unavailable(activity)))
                .onError(e -> main.post(() -> unavailable(activity)))
                .invoke())
            .onUnsuccessful(r -> main.post(() -> unavailable(activity)))
            .onError(e -> main.post(() -> unavailable(activity)))
            .invoke();
    }

    private static void build(@NonNull Activity activity, @NonNull String vmId,
                              @NonNull String vmName, @Nullable JSONObject rules,
                              @NonNull List<UsbHostDeviceInfo> devices) {
        var root = rules == null ? new JSONObject() : rules;
        var choices = new ArrayList<Choice>();
        choices.add(new Choice(UsbRuleLayer.ANY, null, null,
            activity.getString(R.string.usb_pass_everyone)));
        choices.add(new Choice(UsbRuleLayer.CLASS, null, UsbClasses.HID,
            activity.getString(R.string.usb_pass_hid)));
        for (var device : devices) {
            String where = device.attachedVm == null
                ? activity.getString(R.string.usb_pass_where_android)
                : activity.getString(R.string.usb_pass_where_vm,
                    device.attachedVmName == null ? device.attachedVm : device.attachedVmName);
            choices.add(new Choice(UsbRuleLayer.DEVICE, device.id, null,
                activity.getString(R.string.usb_pass_device_fmt,
                    UsbDeviceNames.cardTitle(activity, device), device.id, where)));
        }
        var labels = new String[choices.size()];
        var before = new boolean[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).label;
            before[i] = hasRule(root, choices.get(i), vmId);
        }
        var after = before.clone();
        new MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.usb_pass_title, vmName))
            .setMultiChoiceItems(labels, after, (d, which, checked) -> after[which] = checked)
            .setPositiveButton(android.R.string.ok, (d, w) ->
                save(activity, root, choices, before, after, vmId))
            .setNeutralButton(R.string.usb_pass_rules_page, (d, w) ->
                activity.startActivity(new Intent(activity, UsbRulesActivity.class)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static boolean hasRule(@NonNull JSONObject rules, @NonNull Choice choice,
                                   @NonNull String vmId) {
        var layer = rules.optJSONArray(choice.layer.key);
        for (int i = 0; layer != null && i < layer.length(); i++) {
            var rule = layer.optJSONObject(i);
            if (rule != null && choice.isRuleFor(rule, vmId)) return true;
        }
        return false;
    }

    /** The rules with every changed row's rules taken out of, or put first into, its layer. */
    private static void save(@NonNull Activity activity, @NonNull JSONObject rules,
                             @NonNull List<Choice> choices, @NonNull boolean[] before,
                             @NonNull boolean[] after, @NonNull String vmId) {
        boolean changed = false;
        try {
            for (int i = 0; i < choices.size(); i++) {
                if (before[i] == after[i]) continue;
                changed = true;
                var choice = choices.get(i);
                var old = rules.optJSONArray(choice.layer.key);
                var layer = new JSONArray();
                if (after[i]) {
                    var rule = new JSONObject();
                    if (choice.id != null) rule.put("id", choice.id);
                    if (choice.cls != null) rule.put("class", choice.cls);
                    rule.put("target", TARGET_VM);
                    rule.put("vm", vmId);
                    layer.put(rule);
                }
                for (int j = 0; old != null && j < old.length(); j++) {
                    var rule = old.optJSONObject(j);
                    if (rule != null && choice.isRuleFor(rule, vmId)) continue;
                    layer.put(old.get(j));
                }
                rules.put(choice.layer.key, layer);
            }
            if (!rules.has("version")) rules.put("version", 1);
        } catch (JSONException e) {
            Log.w(TAG, "could not rewrite the rules", e);
            return;
        }
        if (!changed) return;
        boolean enabled = rules.optBoolean("enabled", true);
        var main = new Handler(Looper.getMainLooper());
        DaemonConnection.getInstance().buildRequest("usb_rules_set")
            .put("rules", rules)
            .onResponse(r -> main.post(() -> Toast.makeText(activity, enabled
                    ? activity.getString(R.string.usb_pass_saved, r.optInt("applied", 0))
                    : activity.getString(R.string.usb_pass_disabled),
                Toast.LENGTH_LONG).show()))
            .onUnsuccessful(r -> main.post(() -> new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.usb_rules_save_failed_title)
                .setMessage(r.optString("message", r.toString()))
                .setPositiveButton(android.R.string.ok, null)
                .show()))
            .onError(e -> main.post(() -> unavailable(activity)))
            .invoke();
        Log.i(TAG, fmt("rules rewritten for %s", vmId));
    }

    private static void unavailable(@NonNull Activity activity) {
        if (activity.isFinishing()) return;
        Toast.makeText(activity, R.string.usb_rules_daemon_unavailable, Toast.LENGTH_LONG).show();
    }

    /** Whether the VM has a USB controller (xHCI) for a passed-through device to go to. */
    private static boolean hasUsbController(@NonNull Activity activity, @NonNull String vmId) {
        try {
            var store = new VMStore(activity);
            var config = store.findById(vmId);
            if (config == null) return false;
            for (var peripheral : VMPeripheralConfig.listOf(config.item))
                if (peripheral.getType() == PeripheralType.XHCI_USB) return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the VM's config", e);
        }
        return false;
    }
}
