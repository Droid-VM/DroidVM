// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
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
import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;

/**
 * Settings, Virtual Machine, USB passthrough manager: every host USB device, and one button per
 * device saying where it should go.
 *
 * <p>This is the direct action, not a rule. Nothing happens until the tick is pressed, and what
 * it then sends is one {@code usb_set_target} per changed row: the daemon detaches whoever held
 * the device, attaches the new holder or deauthorizes it, and pins it so the next rules pass
 * leaves it alone until the device is unplugged. The rules are neither read nor written here --
 * they are the other page.</p>
 */
public final class UsbDevicesActivity extends AppCompatActivity
    implements DaemonConnection.EventListener {
    private static final String TAG = "UsbDevicesActivity";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<UsbDeviceRow> rows = new ArrayList<>();
    private final List<VmEntry> vms = new ArrayList<>();
    /** Each VM's xHCI controller ids, read from vms.json; see {@link VmEntry#controllersOf}. */
    private final Map<String, List<String>> vmControllers = new HashMap<>();
    private View root;
    private MaterialToolbar toolbar;
    private TextView tvStatus;
    private TextView tvEmpty;
    private LinearLayout deviceRows;
    /** Choices the daemon has not been told about yet; guards the back key. */
    private boolean dirty = false;
    /**
     * Whether a run of the apply is still going. One request is out at a time and each can spend
     * seconds inside crosvm's CLI, so a second tap would otherwise start a second chain over the
     * same rows and report the first one's work as a conflict.
     */
    private boolean applying = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_devices);
        root = findViewById(R.id.root);
        toolbar = findViewById(R.id.toolbar);
        tvStatus = findViewById(R.id.tv_status);
        tvEmpty = findViewById(R.id.tv_empty);
        deviceRows = findViewById(R.id.device_rows);
        toolbar.setTitle(R.string.usb_devices_title);
        toolbar.setNavigationOnClickListener(v -> confirmExit());
        toolbar.setOnMenuItemClickListener(this::onMenuItem);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });
    }

    private boolean onMenuItem(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_apply) {
            apply();
            return true;
        }
        return false;
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

    /**
     * Rebuilds the list, carrying every choice that has not been applied yet across to the
     * device it was made about. One that is gone is said out loud rather than dropped in
     * silence: it was about to move something that is no longer there.
     */
    private void applyDevices(@Nullable JSONArray arr) {
        var pending = new HashMap<String, UsbDeviceRow>();
        for (var row : rows) if (row.isChanged()) pending.put(row.key(), row);
        rows.clear();
        for (var device : UsbHostDeviceInfo.fromArray(arr)) rows.add(new UsbDeviceRow(device));
        for (var row : rows) {
            var previous = pending.remove(row.key());
            // A choice the new row refuses is one the device has meanwhile answered for itself
            // -- it landed on that VM -- so it is dropped, and only a device that is gone is
            // worth saying anything about.
            if (previous != null) row.want(previous.wanted());
        }
        for (var gone : pending.values())
            snackbar(getString(R.string.usb_devices_gone,
                UsbDeviceNames.cardTitle(this, gone.device)));
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
        // The rows say which VM holds a device by name, so they are worth redrawing.
        renderRows();
    }

    // The list

    private void renderRows() {
        deviceRows.removeAllViews();
        var inflater = LayoutInflater.from(this);
        for (var row : rows) {
            var view = inflater.inflate(R.layout.item_usb_device, deviceRows, false);
            bindRow(view, row);
            deviceRows.addView(view);
        }
        tvEmpty.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
        refreshDirty();
    }

    private void bindRow(@NonNull View view, @NonNull UsbDeviceRow row) {
        TextView name = view.findViewById(R.id.tv_device_name);
        TextView id = view.findViewById(R.id.tv_device_id);
        TextView path = view.findViewById(R.id.tv_device_path);
        TextView state = view.findViewById(R.id.tv_device_state);
        MaterialButton target = view.findViewById(R.id.btn_device_target);
        name.setText(UsbDeviceNames.cardTitle(this, row.device));
        id.setText(row.device.id);
        path.setText(getString(R.string.usb_devices_path_fmt, row.device.port, row.device.sysfs));
        state.setText(stateOf(row.device));
        // A pinned device is one the rules will not touch again until it is unplugged, and
        // nothing else on the page could tell the user that.
        view.findViewById(R.id.tv_device_note).setVisibility(row.device.held ? VISIBLE : GONE);
        target.setText(targetLabel(row.wanted()));
        target.setOnClickListener(v -> UsbTargetPickerDialog.pickForDevice(this, vms,
            vmControllers, picked -> {
                // Painted from the row, not from the pick: one pick the row refuses, and a
                // button showing a move nothing will make is worse than no move at all.
                if (!row.want(picked)) {
                    toast(getString(R.string.usb_devices_same_vm), LENGTH_SHORT);
                    return;
                }
                target.setText(targetLabel(row.wanted()));
                refreshDirty();
            }));
    }

    /** Where the device is right now, in the daemon's own words. */
    @NonNull
    private String stateOf(@NonNull UsbHostDeviceInfo device) {
        if (device.attachedVm != null)
            return getString(R.string.usb_rules_state_attached, holderOf(device));
        var sink = device.sink;
        if (sink != null && sink.layer != null)
            return getString(R.string.usb_devices_state_sinked_rule, sink.layer.key, sink.index);
        if (!device.authorized) return getString(R.string.usb_devices_state_sinked);
        return getString(R.string.usb_rules_state_host);
    }

    /** The VM holding the device, named with the controller it landed on when there is one. */
    @NonNull
    private String holderOf(@NonNull UsbHostDeviceInfo device) {
        var name = device.attachedVmName == null ? device.attachedVm : device.attachedVmName;
        if (name == null) name = "";
        return device.attachedController == null ? name
            : getString(R.string.usb_rules_vm_label_fmt, name, device.attachedController);
    }

    /** What the button says: where the device will go once the tick is pressed. */
    @NonNull
    private String targetLabel(@NonNull UsbDeviceTarget target) {
        if (target.kind == UsbRules.Target.SINK)
            return getString(R.string.usb_rules_target_sink);
        if (target.kind != UsbRules.Target.VM)
            return getString(R.string.usb_devices_target_host);
        var name = vmName(target.vmId);
        return target.controller == null
            ? getString(R.string.usb_rules_target_vm, name)
            : getString(R.string.usb_rules_target_controller, name, target.controller);
    }

    /** The VM's name, or the bare id for one the daemon no longer lists. */
    @NonNull
    private String vmName(@Nullable String vmId) {
        if (vmId == null) return "";
        for (var vm : vms) if (vm.id.equals(vmId)) return vm.name;
        return vmId;
    }

    // Applying

    private void apply() {
        if (applying) return;
        var queue = new ArrayList<UsbDeviceRow>();
        for (var row : rows) if (row.isChanged()) queue.add(row);
        applying = true;
        setApplyEnabled(false);
        applyNext(queue, 0, new ArrayList<>());
    }

    /** The tick, while a run is out: the page has no other way of saying one is. */
    private void setApplyEnabled(boolean enabled) {
        var item = toolbar.getMenu().findItem(R.id.menu_apply);
        if (item != null) item.setEnabled(enabled);
    }

    /**
     * One request at a time, each answered before the next is sent: two devices swapping between
     * the same two VMs would otherwise interleave, and the second attach could land before the
     * first detach.
     */
    private void applyNext(@NonNull List<UsbDeviceRow> queue, int index,
                           @NonNull List<String> failures) {
        if (index >= queue.size()) {
            finishApply(queue.size() - failures.size(), failures);
            return;
        }
        var row = queue.get(index);
        var target = row.wanted();
        var request = DaemonConnection.getInstance().buildRequest("usb_set_target")
            .put("device", row.device.sysfs)
            .put("target", target.toRuleTarget());
        if (target.kind == UsbRules.Target.VM) {
            request.put("vm_id", target.vmId);
            if (target.controller != null) request.put("controller", target.controller);
        }
        request
            .onResponse(resp -> post(() -> applyNext(queue, index + 1, failures)))
            .onUnsuccessful(resp -> post(() -> {
                failures.add(failure(row, message(resp)));
                applyNext(queue, index + 1, failures);
            }))
            .onError(e -> post(() -> {
                failures.add(failure(row, getString(R.string.usb_rules_daemon_unavailable)));
                applyNext(queue, index + 1, failures);
            }))
            .invoke();
    }

    @NonNull
    private String failure(@NonNull UsbDeviceRow row, @NonNull String reason) {
        return getString(R.string.usb_devices_apply_failed_fmt,
            UsbDeviceNames.cardTitle(this, row.device), reason);
    }

    private void finishApply(int applied, @NonNull List<String> failures) {
        applying = false;
        setApplyEnabled(true);
        if (failures.isEmpty())
            toast(getString(R.string.usb_devices_applied, applied), LENGTH_SHORT);
        else
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.usb_devices_apply_failed_title)
                .setMessage(String.join("\n", failures))
                .setPositiveButton(android.R.string.ok, null)
                .show();
        // The reload is what decides where the page stands: a row that landed now agrees with
        // the daemon, and one that did not keeps its choice and keeps the page dirty.
        loadDevices();
    }

    private void refreshDirty() {
        dirty = false;
        for (var row : rows) if (row.isChanged()) dirty = true;
        toolbar.setSubtitle(dirty ? getString(R.string.usb_rules_unsaved) : null);
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
            case "usb_auto_sinked":
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

    private void confirmExit() {
        if (!dirty) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usb_rules_discard_title)
            .setMessage(R.string.usb_devices_discard_message)
            .setPositiveButton(R.string.back_ask_discard, (d, w) -> finish())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

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

    private void toast(@Nullable String text, int duration) {
        Toast.makeText(this, text, duration).show();
    }

    /** Runs on the main thread, unless the page is already going away. */
    private void post(@NonNull Runnable task) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            task.run();
        });
    }
}
