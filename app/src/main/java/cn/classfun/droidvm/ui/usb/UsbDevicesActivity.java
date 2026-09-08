// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.daemon.usb.UsbHostDevice;
import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;

/**
 * Settings, Virtual Machine, USB passthrough manager: every host USB device, and one menu per
 * device saying where it should go.
 *
 * <p>This is the direct action, not a rule, and it has no save button: picking a row of the menu
 * sends one {@code usb_set_target} there and then, and cancelling the menu does nothing. What a
 * row shows is read from the device itself -- which VM holds it, or the host, or nobody -- so
 * there is no pending state to keep, nothing to discard on the way out, and no way for the page
 * to disagree with the daemon about where a device is.</p>
 *
 * <p>The menu's current value is the whole of that display. The left column of a row names the
 * device and says nothing about what it is doing, because a second wording of the same answer
 * is a second thing to keep true -- and the one place a user changes the answer is the better
 * place to read it.</p>
 *
 * <p>The two rows that are not a VM carry a lock, because that is what choosing them adds: the
 * host and "nobody" are states a device can already be in by itself, and asking for one is how
 * a user says the rules may not decide it again until it is unplugged.</p>
 */
public final class UsbDevicesActivity extends AppCompatActivity
    implements DaemonConnection.EventListener {
    private static final String TAG = "UsbDevicesActivity";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<UsbHostDeviceInfo> devices = new ArrayList<>();
    private final List<VmEntry> vms = new ArrayList<>();
    /** Each VM's xHCI controller ids, read from vms.json; see {@link VmEntry#controllersOf}. */
    private final Map<String, List<String>> vmControllers = new HashMap<>();
    private View root;
    private TextView tvStatus;
    private TextView tvEmpty;
    private LinearLayout deviceRows;
    /**
     * Whether one action is still out. A move can spend seconds inside crosvm's CLI, and a
     * second one started over the same devices meanwhile would race it -- so every menu on the
     * page is closed to taps until the daemon has answered, which is also the only way this
     * page has of saying that something is happening.
     */
    private boolean acting = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_devices);
        root = findViewById(R.id.root);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        tvStatus = findViewById(R.id.tv_status);
        tvEmpty = findViewById(R.id.tv_empty);
        deviceRows = findViewById(R.id.device_rows);
        toolbar.setTitle(R.string.usb_devices_title);
        // Nothing is held back to be saved, so leaving takes nothing with it and the back key
        // means what it says everywhere else.
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();
        DaemonConnection.getInstance().addListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        DaemonConnection.getInstance().removeListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        refreshVms();
        loadDevices();
    }

    /** The VM list and, from vms.json, the controllers a device can be sent to. */
    private void refreshVms() {
        vmControllers.clear();
        vmControllers.putAll(VmEntry.controllersOf(this));
        loadVms();
    }

    // Daemon requests. Every callback arrives on the connection's executor, so each hops to
    // the main thread through post(), which also drops it once the page is going away.

    private void loadDevices() {
        DaemonConnection.getInstance().buildRequest("usb_host_list")
            .onResponse(resp -> post(() -> applyDevices(resp.optJSONArray("devices"))))
            .onUnsuccessful(resp -> post(() -> showStatus(message(resp))))
            .onError(e -> post(() -> showStatus(getString(R.string.usb_rules_daemon_unavailable))))
            .invoke();
    }

    private void applyDevices(@Nullable JSONArray arr) {
        devices.clear();
        devices.addAll(UsbHostDeviceInfo.fromArray(arr));
        showStatus(null);
        renderRows();
    }

    private void loadVms() {
        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(resp -> post(() -> applyVms(resp.optJSONArray("data"))))
            .onUnsuccessful(resp -> Log.w(TAG, fmt("vm_list: %s", message(resp))))
            .onError(e -> Log.w(TAG, "vm_list failed", e))
            .invoke();
    }

    private void applyVms(@Nullable JSONArray arr) {
        vms.clear();
        vms.addAll(VmEntry.fromArray(arr));
        // The rows say which VM holds a device by name, and the menu offers the running ones.
        renderRows();
    }

    // The list

    private void renderRows() {
        deviceRows.removeAllViews();
        var inflater = LayoutInflater.from(this);
        for (var device : devices) {
            var view = inflater.inflate(R.layout.item_usb_device, deviceRows, false);
            bindRow(view, device);
            deviceRows.addView(view);
        }
        tvEmpty.setVisibility(devices.isEmpty() ? VISIBLE : GONE);
    }

    /**
     * The left column is what the device IS -- its name, its id and where it is plugged in --
     * and not a word about what it is doing: that is the menu's value, on the right, in one
     * place.
     */
    private void bindRow(@NonNull View view, @NonNull UsbHostDeviceInfo device) {
        TextView name = view.findViewById(R.id.tv_device_name);
        TextView id = view.findViewById(R.id.tv_device_id);
        TextView path = view.findViewById(R.id.tv_device_path);
        MaterialButton target = view.findViewById(R.id.btn_device_target);
        name.setText(UsbDeviceNames.cardTitle(this, device));
        id.setText(device.id);
        path.setText(getString(R.string.usb_devices_path_fmt, device.port, device.sysfs));
        target.setText(valueOf(device));
        target.setEnabled(!acting);
        target.setOnClickListener(v -> UsbTargetPickerDialog.pickForDevice(this, vms,
            vmControllers, picked -> setTarget(device, picked)));
    }

    /**
     * What the menu shows as this row's value, read from the device the moment the row is drawn
     * -- and the whole of what the page says about where the device is.
     *
     * <p>A host or idle device the user never asked for is a value the menu does not offer: it
     * is where the rules, or the gate, happen to have left the device, and showing it as the
     * locked option would claim a decision nobody made. So it shows as the plain word, and only
     * a locked device reads back as one of the two rows that lock -- which is also the only
     * place the lock is said, now that the row carries no note under it.</p>
     *
     * <p>A device somebody else claimed through usbfs gets a sixth value of its own, for the
     * same reason: it is on no VM of ours, and it is not on the host either, so every word the
     * menu speaks would be a lie about it.</p>
     */
    @NonNull
    private String valueOf(@NonNull UsbHostDeviceInfo device) {
        var current = UsbDeviceTarget.current(device.state, device.attachedVm,
            device.attachedController);
        // A usbfs claim with no attachment of ours behind it: an Android app that opened the
        // device, or a VMM still dying with it before the leftovers take the claim off. No lock
        // marker on it whatever the lock says -- the marker names one of the two rows the menu
        // offers, and this is a state nobody could have picked.
        if (current == null) return getString(R.string.usb_devices_target_claimed);
        if (current.kind == UsbRules.Target.VM) {
            var name = vmName(current.vmId);
            // The same shape the menu's own VM rows carry, so the value the button shows is
            // recognisable as one of them; a device attached before controllers were recorded
            // names none, and the bare VM name is the whole of what is known about it.
            return current.controller == null ? name
                : getString(R.string.usb_rules_vm_label_fmt, name, current.controller);
        }
        // "Idle" on this page, "Sink" in a rule: the same target, named for what it is here.
        // A rule says where a device is to be sent; this button says where the device is, and a
        // device nobody holds is idle -- the state's own word, the one UsbHostDevice.State uses.
        var label = current.kind == UsbRules.Target.SINK
            ? R.string.usb_target_idle : R.string.usb_target_host;
        return device.locked ? UsbTargetPickerDialog.lockedLabel(this, label) : getString(label);
    }

    /** The VM's name, or the bare id for one the daemon no longer lists. */
    @NonNull
    private String vmName(@Nullable String vmId) {
        if (vmId == null) return "";
        for (var vm : vms) if (vm.id.equals(vmId)) return vm.name;
        return vmId;
    }

    // The action

    /**
     * One picked row of the menu, sent as it is picked. Choosing what the device already shows
     * is not a no-op: for the host and for nobody it is what locks the device, which is the
     * whole reason those two rows exist.
     */
    private void setTarget(@NonNull UsbHostDeviceInfo device, @NonNull UsbDeviceTarget target) {
        if (acting) return;
        setActing(true);
        var request = DaemonConnection.getInstance().buildRequest("usb_set_target")
            .put("device", device.sysfs)
            .put("target", target.toRuleTarget());
        if (target.kind == UsbRules.Target.VM) {
            request.put("vm_id", target.vmId);
            if (target.controller != null) request.put("controller", target.controller);
        }
        request
            .onResponse(resp -> post(() ->
                finished(device, target, resp.optString("state", ""), null)))
            .onUnsuccessful(resp -> post(() -> finished(device, target, "", message(resp))))
            .onError(e -> post(() -> finished(device, target, "",
                getString(R.string.usb_rules_daemon_unavailable))))
            .invoke();
    }

    /**
     * What the daemon made of it. The state it read back is what the row will show after the
     * reload, and it is also the one answer worth a sentence: choosing nobody takes the host's
     * drivers off the device, and a driver that will not let go is the one way that can fail
     * silently -- the row would simply come back saying the host still has it.
     */
    private void finished(@NonNull UsbHostDeviceInfo device, @NonNull UsbDeviceTarget target,
                          @NonNull String state, @Nullable String error) {
        setActing(false);
        if (error != null)
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.usb_devices_action_failed_title)
                .setMessage(error)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        else if (target.kind == UsbRules.Target.SINK
            && UsbHostDevice.State.HOSTUSE.key.equals(state))
            snackbar(getString(R.string.usb_devices_idle_kept,
                UsbDeviceNames.cardTitle(this, device)));
        loadDevices();
    }

    /** Closes or reopens every menu on the page, which the rows read as they are drawn. */
    private void setActing(boolean value) {
        acting = value;
        renderRows();
    }

    // DaemonConnection.EventListener; called off the main thread.

    @Override
    public void onDaemonEvent(JSONObject msg) {
        if (!msg.optString("type", "").equals("event")) return;
        var data = msg.optJSONObject("data");
        if (data == null) return;
        var event = data.optString("event", "");
        switch (event) {
            case "usb_host_changed": {
                // The event carries the fresh list; only an older daemon makes us ask.
                var arr = data.optJSONArray("devices");
                post(() -> {
                    if (arr != null) applyDevices(arr);
                    else loadDevices();
                });
                break;
            }
            case "usb_vm_changed":
            case "usb_auto_attached":
                post(this::loadDevices);
                break;
            case "output":
                break;
            default:
                // A VM state event: which VMs can take a device just changed. The controllers
                // are not re-read for it -- only the editor changes those, and this page is not
                // open while it is.
                if (data.has("state")) post(this::loadVms);
                break;
        }
    }

    @Override
    public void onDaemonConnected() {
        post(this::refreshAll);
    }

    @Override
    public void onDaemonDisconnected() {
        post(() -> showStatus(getString(R.string.usb_rules_daemon_unavailable)));
    }

    // Helpers

    /** A line at the top for what stops the page working; hidden when nothing does. */
    private void showStatus(@Nullable String message) {
        if (message == null || message.isEmpty()) {
            tvStatus.setVisibility(GONE);
            return;
        }
        tvStatus.setText(message);
        tvStatus.setVisibility(VISIBLE);
    }

    @NonNull
    private String message(@NonNull JSONObject resp) {
        var text = resp.optString("message", "");
        return text.isEmpty() ? getString(R.string.usb_rules_request_failed) : text;
    }

    private void snackbar(@NonNull String text) {
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show();
    }

    /** Runs on the main thread, unless the page is already going away. */
    private void post(@NonNull Runnable task) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            task.run();
        });
    }
}
