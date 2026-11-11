// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.app.Activity;
import android.content.Context;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/**
 * The console's USB passthrough: which of the plugged-in devices this VM has now, and two rows
 * that stand for many -- every device, and every HID device (keyboards, mice) -- as rules of the
 * one shared rule set.
 *
 * <p>A device row is a move, not a rule: checked, the device goes to this VM now; unchecked, it
 * goes back to Android, kept there by the daemon's lock. Either lasts until the device is
 * unplugged -- plugged back in, it follows the rules again -- and nothing is written to the rule
 * set, so using a device for a while does not make it this VM's for good. A device that should
 * always come here is a rule on the rules page.</p>
 *
 * <p>The two meta rows are rules, as they have to be -- they are about devices plugged in later:
 * "every device" a catch-all rule, "HID devices" a class rule for class 03, the rules page
 * showing and editing them. A rule written here goes first in its layer, the user having just
 * said so; unchecking removes this VM's rule for that row and no other VM's, and a row left as it
 * was leaves its rules where they were. The daemon applies the set as it saves it, to what is
 * plugged in now. The moves go first, so a device given back to Android is locked there before
 * a new rule could take it.</p>
 *
 * <p>In place of the VNC projection page's USB HID capture, which took a USB keyboard or mouse
 * from Android and spoke boot-protocol reports over RFB: the guest gets the device itself.</p>
 */
public final class UsbPassthroughDialog {
    private static final String TAG = "UsbPassthroughDialog";
    private static final String TARGET_VM = "vm";

    /** One row of the dialog: a plugged-in device to move, or the rule it stands for. */
    private static final class Choice {
        /** The device a device row moves; null for a rule row. */
        @Nullable
        final UsbHostDeviceInfo device;
        /** The layer a rule row's rule goes in; null for a device row. */
        @Nullable
        final UsbRuleLayer layer;
        @Nullable
        final String cls;
        final String label;

        private Choice(@Nullable UsbHostDeviceInfo device, @Nullable UsbRuleLayer layer,
                       @Nullable String cls, @NonNull String label) {
            this.device = device;
            this.layer = layer;
            this.cls = cls;
            this.label = label;
        }

        static Choice rule(@NonNull UsbRuleLayer layer, @Nullable String cls,
                           @NonNull String label) {
            return new Choice(null, layer, cls, label);
        }

        static Choice device(@NonNull UsbHostDeviceInfo device, @NonNull String label) {
            return new Choice(device, null, null, label);
        }

        /** Whether [rule] is this VM's rule for this rule row. */
        boolean isRuleFor(@NonNull JSONObject rule, @NonNull String vmId) {
            return TARGET_VM.equals(rule.optString("target", rule.has("vm") ? TARGET_VM : ""))
                && vmId.equals(rule.optString("vm", ""))
                && rule.isNull("id")
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
        choices.add(Choice.rule(UsbRuleLayer.ANY, null,
            activity.getString(R.string.usb_pass_everyone)));
        choices.add(Choice.rule(UsbRuleLayer.CLASS, UsbClasses.HID,
            activity.getString(R.string.usb_pass_hid)));
        for (var device : devices) {
            String where = device.attachedVm == null
                ? activity.getString(R.string.usb_pass_where_android)
                : activity.getString(R.string.usb_pass_where_vm,
                    device.attachedVmName == null ? device.attachedVm : device.attachedVmName);
            choices.add(Choice.device(device, activity.getString(R.string.usb_pass_device_fmt,
                UsbDeviceNames.cardTitle(activity, device), device.id, where)));
        }
        var labels = new String[choices.size()];
        var before = new boolean[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            var choice = choices.get(i);
            labels[i] = choice.label;
            before[i] = choice.device != null
                ? vmId.equals(choice.device.attachedVm) : hasRule(root, choice, vmId);
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
        var layer = choice.layer == null ? null : rules.optJSONArray(choice.layer.key);
        for (int i = 0; layer != null && i < layer.length(); i++) {
            var rule = layer.optJSONObject(i);
            if (rule != null && choice.isRuleFor(rule, vmId)) return true;
        }
        return false;
    }

    /**
     * The changed device rows moved, then the rules with every changed rule row's rule taken out
     * of, or put first into, its layer.
     */
    private static void save(@NonNull Activity activity, @NonNull JSONObject rules,
                             @NonNull List<Choice> choices, @NonNull boolean[] before,
                             @NonNull boolean[] after, @NonNull String vmId) {
        var moves = new ArrayList<Integer>();
        boolean rulesChanged = false;
        try {
            for (int i = 0; i < choices.size(); i++) {
                if (before[i] == after[i]) continue;
                var choice = choices.get(i);
                if (choice.layer == null) {
                    moves.add(i);
                    continue;
                }
                rulesChanged = true;
                var old = rules.optJSONArray(choice.layer.key);
                var layer = new JSONArray();
                if (after[i]) {
                    var rule = new JSONObject();
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
        if (moves.isEmpty() && !rulesChanged) return;
        boolean saveRules = rulesChanged;
        var app = activity.getApplicationContext();
        var main = new Handler(Looper.getMainLooper());
        runOnPool(() -> {
            var done = new ArrayList<String>();
            var failed = new ArrayList<String>();
            int moved = 0;
            for (int i : moves) {
                var device = Objects.requireNonNull(choices.get(i).device);
                var target = after[i] ? UsbDeviceTarget.vm(vmId, null) : UsbDeviceTarget.host();
                var error = request(app, req -> {
                    req.put("command", "usb_set_target");
                    req.put("device", device.sysfs);
                    req.put("target", target.toRuleTarget());
                    if (after[i]) req.put("vm_id", vmId);
                }, null);
                if (error == null) moved++;
                else failed.add(fmt("%s: %s", UsbDeviceNames.cardTitle(app, device), error));
            }
            if (moved > 0) done.add(app.getString(R.string.usb_pass_moved, moved));
            if (saveRules) {
                var resp = new JSONObject[1];
                var error = request(app, req -> {
                    req.put("command", "usb_rules_set");
                    req.put("rules", rules);
                }, resp);
                if (error != null)
                    failed.add(fmt("%s: %s", app.getString(R.string.usb_pass_rules_page), error));
                else if (rules.optBoolean("enabled", true))
                    done.add(app.getString(R.string.usb_pass_saved,
                        resp[0].optInt("applied", 0)));
                else
                    done.add(app.getString(R.string.usb_pass_disabled));
            }
            Log.i(TAG, fmt("%d device(s) moved, rules %s for %s", moved,
                saveRules ? (failed.isEmpty() ? "saved" : "not all done") : "untouched", vmId));
            main.post(() -> {
                if (!done.isEmpty())
                    Toast.makeText(app, String.join("\n", done), Toast.LENGTH_LONG).show();
                if (!failed.isEmpty() && !activity.isFinishing())
                    new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.usb_pass_failed_title)
                        .setMessage(String.join("\n", failed))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private interface RequestFiller {
        void fill(@NonNull JSONObject req) throws JSONException;
    }

    /**
     * One daemon request, on the calling (pool) thread; null when it succeeded, else what to say
     * about it. [out], when given, gets the response.
     */
    @Nullable
    private static String request(@NonNull Context context, @NonNull RequestFiller filler,
                                  @Nullable JSONObject[] out) {
        try {
            var req = new JSONObject();
            filler.fill(req);
            var resp = DaemonConnection.getInstance().request(req);
            if (out != null) out[0] = resp;
            if (resp.optBoolean("success", true)) return null;
            var text = resp.optString("message", "");
            return text.isEmpty() ? context.getString(R.string.usb_rules_request_failed) : text;
        } catch (IOException | JSONException e) {
            return context.getString(R.string.usb_rules_daemon_unavailable);
        }
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
