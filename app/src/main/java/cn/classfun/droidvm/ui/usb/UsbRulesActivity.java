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
import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.ui.widgets.container.CardItemListView;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;

/**
 * Settings, Virtual Machine, USB passthrough: the automatic attach rules of plan section 2.4.
 *
 * <p>The daemon owns the rules. The page reads them with {@code usb_rules_get}, edits a copy
 * held in four adapters and the master switch at the top, and pushes the whole object back with
 * {@code usb_rules_set}; it never touches the rules file itself. {@code usb_host_list} and
 * {@code vm_list} only give the rows and the pickers something readable to show. While the page
 * is open it listens on the daemon event stream, so a plug, an unplug or an automatic attach
 * shows up without a reload.</p>
 */
public final class UsbRulesActivity extends AppCompatActivity
    implements DaemonConnection.EventListener, UsbRuleAdapter.Listener {
    private static final String TAG = "UsbRulesActivity";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final EnumMap<UsbRuleLayer, UsbRuleAdapter> adapters =
        new EnumMap<>(UsbRuleLayer.class);
    private final List<UsbHostDeviceInfo> devices = new ArrayList<>();
    private final List<VmEntry> vms = new ArrayList<>();
    /** Each VM's xHCI controller ids, read from vms.json; see {@link VmEntry#controllersOf}. */
    private final Map<String, List<String>> vmControllers = new HashMap<>();
    private View root;
    private MaterialToolbar toolbar;
    private TextView tvStatus;
    /**
     * The master switch, which is an edit like any other: it takes effect when the page is
     * saved, and saving it off is what makes the daemon give every device back. The zones below
     * it stay editable while it is off, so rules can be prepared before they are allowed to run.
     */
    private SwitchRowWidget swEnabled;
    /** Edits not yet pushed to the daemon; guards both the back key and the resume reload. */
    private boolean dirty = false;
    /** True while the daemon's own answer is being written into the switch, which is no edit. */
    private boolean applying = false;
    /**
     * True while the switch's warning is on screen. The page is not dirty yet -- the edit is
     * made when the dialog is answered -- so without this a reload arriving in that window
     * would write the daemon's own value over a switch the user is being asked about, and the
     * OK they then tap would mark a page whose switch had been turned back off underneath it.
     */
    private boolean confirming = false;
    /**
     * Whether the cards show their drag handle and delete button. Off by default: a page that is
     * mostly read -- "which rule takes this device" -- should not open with a delete button on
     * every row, and the long-press drag works in either mode for whoever already knows it.
     */
    private boolean editing = false;

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
        swEnabled = findViewById(R.id.sw_usb_enabled);
        swEnabled.setOnCheckedChangeListener(() -> {
            if (applying) return;
            if (swEnabled.isChecked()) confirmEnable();
            else setDirty(true);
        });
        bindList(R.id.list_exact, UsbRuleLayer.EXACT);
        bindList(R.id.list_port, UsbRuleLayer.PORT);
        bindList(R.id.list_device, UsbRuleLayer.DEVICE);
        bindList(R.id.list_any, UsbRuleLayer.ANY);
    }

    /**
     * The warning the switch raises on its way on, before the save can act on it.
     *
     * <p>Turning passthrough on is not a preference among the rules: it shuts the autoprobe gate,
     * and from then on a plugged-in device binds no driver of its own and reaches Android only
     * because a rule sent it there. That is worth saying once, in front of the change, rather
     * than leaving it to be discovered by a keyboard that stops typing. Refusing puts the switch
     * back without an edit; turning it off asks nothing, because giving control back is the
     * direction nobody needs warning about.</p>
     */
    private void confirmEnable() {
        confirming = true;
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.usb_rules_enable_warning_title)
            .setMessage(R.string.usb_rules_enable_warning_message)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                confirming = false;
                setDirty(true);
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> revertEnable())
            .setOnCancelListener(d -> revertEnable())
            .show();
    }

    /** The switch back to off, written the way the daemon's own answer is: not an edit. */
    private void revertEnable() {
        confirming = false;
        applying = true;
        swEnabled.setChecked(false);
        applying = false;
    }

    private void bindList(int viewId, @NonNull UsbRuleLayer layer) {
        CardItemListView list = findViewById(viewId);
        var adapter = new UsbRuleAdapter(this, layer, this);
        list.setAdapter(adapter);
        // The handle can only ask; the list is what holds the ItemTouchHelper that can lift a
        // row, and a card has no way to reach it otherwise.
        adapter.setDragStarter(list::startDrag);
        adapters.put(layer, adapter);
    }

    /**
     * Turns edit mode on or off across all four zones at once. The zones are one list broken
     * into four by priority, and a mode that was on in one of them and off in the next would be
     * four modes.
     */
    private void setEditing(boolean value) {
        editing = value;
        for (var adapter : adapters.values()) adapter.setEditing(value);
        var item = toolbar.getMenu().findItem(R.id.menu_edit);
        if (item == null) return;
        // Not a tick: the save button beside it is one, and two ticks in a row is a toolbar
        // where the destructive-looking one and the one that writes the file look the same.
        item.setIcon(value ? R.drawable.ic_close : R.drawable.ic_edit);
        item.setTitle(value ? R.string.usb_rules_edit_done : R.string.usb_rules_edit);
    }

    @NonNull
    private UsbRuleAdapter adapterOf(@NonNull UsbRuleLayer layer) {
        var adapter = adapters.get(layer);
        if (adapter == null) throw new IllegalStateException(fmt("no adapter for %s", layer));
        return adapter;
    }

    private boolean onMenuItem(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_edit) {
            setEditing(!editing);
            return true;
        }
        if (id == R.id.menu_save) {
            save();
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
        // A switch waiting for its warning to be answered is an edit in the making, and reading
        // the daemon's value over it would be clobbering one.
        if (!dirty && !confirming) loadRules();
    }

    private void loadControllers() {
        vmControllers.clear();
        vmControllers.putAll(VmEntry.controllersOf(this));
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
        applying = true;
        try {
            // Absent reads as on, the way the daemon reads it: that is what every rules file
            // written before there was a switch means.
            swEnabled.setChecked(rules == null || rules.optBoolean("enabled", true));
            for (var layer : UsbRuleLayer.values()) {
                var arr = rules == null ? null : rules.optJSONArray(layer.key);
                adapterOf(layer).setItems(arr == null ? DataItem.newArray() : new DataItem(arr));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Malformed rules from daemon", e);
            showStatus(e.getMessage());
            return;
        } finally {
            applying = false;
        }
        setDirty(false);
        showStatus(null);
    }

    /** The whole rules object in the wire shape; the daemon validates it, not the page. */
    @NonNull
    private JSONObject buildRules() throws JSONException {
        var rules = new JSONObject();
        rules.put("version", 1);
        rules.put("enabled", swEnabled.isChecked());
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
        // Read before the request rather than in the callback: the count that comes back is
        // about the save that was sent, and the save that turns the switch off counts devices
        // given back rather than taken. "Applied to 3" after turning passthrough off would read
        // as three devices taken, which is the opposite of what just happened.
        var enabled = swEnabled.isChecked();
        DaemonConnection.getInstance().buildRequest("usb_rules_set")
            .put("rules", rules)
            .onResponse(resp -> post(() -> {
                setDirty(false);
                showStatus(null);
                toast(getString(enabled ? R.string.usb_rules_saved : R.string.usb_rules_released,
                    resp.optInt("applied", 0)), LENGTH_SHORT);
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
        pushLookups();
    }

    private void pushLookups() {
        for (var adapter : adapters.values()) adapter.setLookups(devices, vms, vmControllers);
    }

    // UsbRuleAdapter.Listener

    /**
     * Two questions rather than one dialog: what the rule is about, then where the device goes.
     *
     * <p>A rule with no target is not a legal catch-all rule, and defaulting the other layers to
     * "keep it on the host" would quietly add a row that does nothing. Cancelling either step
     * adds nothing.</p>
     */
    @Override
    public void onAddRule(@NonNull UsbRuleLayer layer) {
        // A second row in the last layer can never be reached, whatever it targets, so the
        // zone is full rather than the row being added and refused by the save.
        if (layer == UsbRuleLayer.ANY && adapterOf(layer).getItemCount() > 0) {
            toast(getString(R.string.usb_rules_any_taken), LENGTH_SHORT);
            return;
        }
        if (layer.needsSubject())
            UsbSubjectPickerDialog.pick(this, layer, devices, this::askTarget);
        else askTarget(layer, null, null);
    }

    private void askTarget(@NonNull UsbRuleLayer layer, @Nullable String id,
                           @Nullable String port) {
        UsbTargetPickerDialog.pick(this, layer, vms, vmControllers, target -> {
            var rule = DataItem.newObject();
            if (layer.hasId) rule.set("id", id);
            if (layer.hasPort) rule.set("port", port);
            rule.set("target", target.toRuleTarget());
            if (target.kind == UsbRules.Target.VM) {
                rule.set("vm", target.vmId);
                if (target.controller != null) rule.set("controller", target.controller);
            }
            adapterOf(layer).addRule(rule);
        });
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
