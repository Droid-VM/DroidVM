// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.store.vm.VMXhciConfig;
import cn.classfun.droidvm.ui.widgets.container.CardItemListView;

/**
 * Settings, Virtual Machine, USB passthrough: the automatic attach rules of plan section 2.4.
 *
 * <p>The daemon owns the rules. The page reads them with {@code usb_rules_get}, edits a copy
 * held in four adapters and pushes the whole object back with {@code usb_rules_set}; it never
 * touches the rules file itself. {@code usb_host_list} and {@code vm_list} only give the rows
 * and the pickers something readable to show; {@code usb_rules_test} is the dry run behind the
 * preview button. While the page is open it listens on the daemon event stream, so a plug, an
 * unplug or an automatic attach shows up without a reload.</p>
 */
public final class UsbRulesActivity extends AppCompatActivity
    implements DaemonConnection.EventListener, UsbRuleAdapter.Listener {
    private static final String TAG = "UsbRulesActivity";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final EnumMap<UsbRuleLayer, UsbRuleAdapter> adapters =
        new EnumMap<>(UsbRuleLayer.class);
    private final List<UsbHostDeviceInfo> devices = new ArrayList<>();
    private final List<VmEntry> vms = new ArrayList<>();
    private final Map<String, String> vmNames = new HashMap<>();
    /** Each VM's xHCI controller ids, read from vms.json; see {@link #loadControllers}. */
    private final Map<String, List<String>> vmControllers = new HashMap<>();
    private View root;
    private MaterialToolbar toolbar;
    private TextView tvStatus;
    /** Edits not yet pushed to the daemon; guards both the back key and the resume reload. */
    private boolean dirty = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_rules);
        root = findViewById(R.id.root);
        toolbar = findViewById(R.id.toolbar);
        tvStatus = findViewById(R.id.tv_status);
        toolbar.setTitle(R.string.usb_rules_title);
        toolbar.setNavigationOnClickListener(v -> confirmExit());
        toolbar.setOnMenuItemClickListener(this::onMenuItem);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });
        bindList(R.id.list_exact, UsbRuleLayer.EXACT);
        bindList(R.id.list_port, UsbRuleLayer.PORT);
        bindList(R.id.list_device, UsbRuleLayer.DEVICE);
        bindList(R.id.list_any, UsbRuleLayer.ANY);
        findViewById(R.id.btn_preview).setOnClickListener(v -> preview());
    }

    private void bindList(int viewId, @NonNull UsbRuleLayer layer) {
        CardItemListView list = findViewById(viewId);
        var adapter = new UsbRuleAdapter(this, layer, this);
        list.setAdapter(adapter);
        adapters.put(layer, adapter);
    }

    @NonNull
    private UsbRuleAdapter adapterOf(@NonNull UsbRuleLayer layer) {
        var adapter = adapters.get(layer);
        if (adapter == null) throw new IllegalStateException(fmt("no adapter for %s", layer));
        return adapter;
    }

    private boolean onMenuItem(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_save) {
            save();
            return true;
        }
        if (id == R.id.menu_preview) {
            preview();
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

    /** Names and devices always; the rules only while there is nothing unsaved to clobber. */
    private void refreshAll() {
        loadControllers();
        loadVms();
        loadDevices();
        if (!dirty) loadRules();
    }

    /**
     * Which xHCI controllers each VM has, from vms.json rather than from {@code vm_list}.
     *
     * <p>This is what decides whether a rule's target still exists, and only the config file can
     * say: the daemon's copy of a VM is loaded once at daemon start and replaced only when a VM
     * is created or started, so a controller added in the editor would read as missing here and
     * one deleted there would go on reading as a healthy target until the VM was next started --
     * wrong in both directions, on the one surface that reports a dangling rule.</p>
     */
    private void loadControllers() {
        var store = new VMStore();
        store.load(this);
        vmControllers.clear();
        store.forEach((id, config) -> {
            if (id == null) return;
            var ids = new ArrayList<String>();
            for (var controller : VMXhciConfig.listControllers(config.item))
                if (!controller.getControllerId().isEmpty())
                    ids.add(controller.getControllerId());
            vmControllers.put(id.toString(), ids);
        });
        pushLookups();
    }

    // Daemon requests. Every callback arrives on the connection's executor, so each hops to
    // the main thread through post(), which also drops it once the page is going away.

    private void loadRules() {
        DaemonConnection.getInstance().buildRequest("usb_rules_get")
            .onResponse(resp -> post(() -> applyRules(resp.optJSONObject("rules"))))
            .onUnsuccessful(resp -> post(() -> showStatus(message(resp))))
            .onError(e -> post(() -> showStatus(getString(R.string.usb_rules_daemon_unavailable))))
            .invoke();
    }

    private void applyRules(@Nullable JSONObject rules) {
        try {
            for (var layer : UsbRuleLayer.values()) {
                var arr = rules == null ? null : rules.optJSONArray(layer.key);
                adapterOf(layer).setItems(arr == null ? DataItem.newArray() : new DataItem(arr));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Malformed rules from daemon", e);
            showStatus(e.getMessage());
            return;
        }
        setDirty(false);
        showStatus(null);
    }

    /** The whole rules object in the wire shape; the daemon validates it, not the page. */
    @NonNull
    private JSONObject buildRules() throws JSONException {
        var rules = new JSONObject();
        rules.put("version", 1);
        for (var layer : UsbRuleLayer.values())
            rules.put(layer.key, adapterOf(layer).getItems().toJsonArray());
        return rules;
    }

    private void save() {
        JSONObject rules;
        try {
            rules = buildRules();
        } catch (JSONException e) {
            toast(e.getMessage(), LENGTH_LONG);
            return;
        }
        DaemonConnection.getInstance().buildRequest("usb_rules_set")
            .put("rules", rules)
            .onResponse(resp -> post(() -> {
                setDirty(false);
                showStatus(null);
                toast(getString(R.string.usb_rules_saved, resp.optInt("applied", 0)), LENGTH_SHORT);
                loadDevices();
            }))
            .onUnsuccessful(resp -> post(() -> showSaveError(message(resp))))
            .onError(e -> post(() ->
                toast(getString(R.string.usb_rules_daemon_unavailable), LENGTH_LONG)))
            .invoke();
    }

    private void showSaveError(@NonNull String message) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usb_rules_save_failed_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /** Dry run of the rules the daemon holds now -- the saved ones, not the unsaved edits. */
    private void preview() {
        DaemonConnection.getInstance().buildRequest("usb_rules_test")
            .onResponse(resp -> post(() ->
                UsbRulesPreviewDialog.show(this, resp.optJSONArray("devices"), devices, vmNames)))
            .onUnsuccessful(resp -> post(() -> toast(message(resp), LENGTH_LONG)))
            .onError(e -> post(() ->
                toast(getString(R.string.usb_rules_daemon_unavailable), LENGTH_LONG)))
            .invoke();
    }

    private void loadDevices() {
        DaemonConnection.getInstance().buildRequest("usb_host_list")
            .onResponse(resp -> post(() -> applyDevices(resp.optJSONArray("devices"))))
            .onUnsuccessful(resp -> Log.w(TAG, fmt("usb_host_list: %s", message(resp))))
            .onError(e -> Log.w(TAG, "usb_host_list failed", e))
            .invoke();
    }

    private void applyDevices(@Nullable JSONArray arr) {
        devices.clear();
        devices.addAll(UsbHostDeviceInfo.fromArray(arr));
        pushLookups();
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
        vmNames.clear();
        for (var vm : vms) vmNames.put(vm.id, vm.name);
        pushLookups();
    }

    private void pushLookups() {
        for (var adapter : adapters.values()) adapter.setLookups(devices, vms, vmControllers);
    }

    // UsbRuleAdapter.Listener

    @Override
    public void onAddRule(@NonNull UsbRuleLayer layer) {
        UsbRuleAddDialog.show(this, layer, devices, vms, rule -> adapterOf(layer).addRule(rule));
    }

    @Override
    public void onRulesChanged() {
        setDirty(true);
    }

    private void setDirty(boolean dirty) {
        this.dirty = dirty;
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
                post(this::loadDevices);
                break;
            case "usb_auto_attached":
                post(() -> {
                    snackbar(getString(R.string.usb_rules_event_attached,
                        deviceLabel(data), data.optString("vm_name", "")));
                    loadDevices();
                });
                break;
            case "usb_auto_failed":
                post(() -> snackbar(getString(R.string.usb_rules_event_failed,
                    deviceLabel(data), data.optString("vm_name", ""),
                    data.optString("error", ""))));
                break;
            case "output":
                break;
            default:
                // A VM state event: which VMs can take a device just changed.
                if (data.has("state")) post(this::loadVms);
                break;
        }
    }

    /** The plugged-in device an auto event names: by sysfs, else by id, else the id itself. */
    @NonNull
    private String deviceLabel(@NonNull JSONObject data) {
        var sysfs = data.optString("sysfs", "");
        var id = data.optString("id", "");
        for (var device : devices)
            if (!sysfs.isEmpty() && device.sysfs.equals(sysfs)) return device.displayName(this);
        for (var device : devices)
            if (!id.isEmpty() && device.id.equals(id)) return device.displayName(this);
        return id.isEmpty() ? sysfs : id;
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
            .setMessage(R.string.usb_rules_discard_message)
            .setPositiveButton(R.string.back_ask_discard, (d, w) -> finish())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** A line under the intro for what stops the page working; hidden when nothing does. */
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
