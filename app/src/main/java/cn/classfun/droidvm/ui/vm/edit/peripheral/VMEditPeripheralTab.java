// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.data.HostAudioDevices;
import cn.classfun.droidvm.lib.store.vm.PeripheralType;
import cn.classfun.droidvm.lib.store.vm.SerialBackend;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VMSerialConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.store.vm.VMXhciConfig;
import cn.classfun.droidvm.ui.usb.UsbHostDeviceInfo;
import cn.classfun.droidvm.ui.usb.UsbRuleLayer;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditTab;
import cn.classfun.droidvm.ui.vm.edit.basic.VMEditBasicTab;
import cn.classfun.droidvm.ui.vm.edit.peripheral.XhciBindingDiff.Row;
import cn.classfun.droidvm.ui.widgets.container.CardItemListView;

/**
 * Peripherals attached to the VM: the audio devices the crosvm backend turns into virtio-snd
 * cards, and the xHCI controllers the guest's USB hangs off.
 *
 * <p>The controller cards are the one place in the editor where a card shows something that is
 * not VM config. What is attached to a controller comes from the app-global USB attach rules,
 * which the daemon owns: they are read once when the editor opens, edited as a local copy, and
 * pushed as a diff after the config has been saved. Everything else here is the usual
 * {@code DataItem} the adapter edits in place.</p>
 */
public final class VMEditPeripheralTab extends VMEditBaseTab
    implements VMPeripheralEditAdapter.XhciHost {
    private static final String TAG = "VMEditPeripheralTab";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** The rules as this page holds them: the snapshot it opened with, and what it shows now. */
    private final XhciBindingStore bindings = new XhciBindingStore();
    /** What is plugged into the phone right now; the rows and the add menu describe it. */
    private final List<UsbHostDeviceInfo> hostDevices = new ArrayList<>();
    private CardItemListView listPeripherals;
    private CardItemListView listSerialPorts;
    private VMPeripheralEditAdapter adapter;
    /**
     * The VM's controller-id counter, carried across the edit.
     *
     * <p>It lives on the config, but the config this tab loaded is not the object the save
     * writes -- {@code doSave} re-reads the store, and a new VM's config is built at save time.
     * So the tab holds the value and writes it back in {@link #saveConfig}.</p>
     */
    private long xhciNext = 0;
    /** A push is in flight; a second Save would otherwise merge against a stale read. */
    private boolean pushing = false;
    /**
     * The master switch as the daemon last reported it. True until told otherwise, which is what
     * an absent "enabled" means to the daemon as well: a card must not claim passthrough is off
     * on the strength of a read that has not come back.
     */
    private boolean usbEnabled = true;

    public VMEditPeripheralTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        listPeripherals = view.findViewById(R.id.list_peripherals);
        listSerialPorts = view.findViewById(R.id.list_serial_ports);
    }

    @Override
    public void initValue() {
        adapter = listPeripherals.setAdapter(VMPeripheralEditAdapter.class);
        adapter.setMicPermissionGate(parent::ensureRecordAudioThen);
        adapter.setCameraPermissionGate(parent.getCameraPermission()::requireThen);
        adapter.setXhciHost(this);
        listSerialPorts.setAdapter(VMSerialEditAdapter.class);
        if (!parent.editMode) {
            var seed = DataItem.newObject();
            var peripherals = DataItem.newArray();
            peripherals.append(VMPeripheralConfig.createDefaultVirtioSound().item);
            seed.set(VMXhciConfig.KEY_PERIPHERALS, peripherals);
            // Through the minting path, so this list is the one createWithCustomizeDefaults
            // builds -- the same card with the same id, whichever of the two the user sees.
            if (VMConfig.NEW_VM_DEFAULT_USB) VMXhciConfig.addController(seed);
            listPeripherals.setItems(seed.opt(VMXhciConfig.KEY_PERIPHERALS, DataItem.newArray()));
        }
        // A brand-new VM never goes through loadConfig, but its serial list is not empty:
        // the fixed COM quartet exists either way, so show it (COM1 as the app console).
        var scratch = DataItem.newObject();
        VMSerialConfig.ensureDefaults(scratch);
        listSerialPorts.setItems(scratch.opt(VMSerialConfig.KEY, DataItem.newArray()));
        loadHostDevices();
    }

    @Override
    public void onTabShown() {
        // The host device list is live: something may have been plugged in or paired since the
        // rows were last bound. The rules are deliberately not re-read -- that is a once-per-
        // session load, and reading it again here would throw away rows the user just added.
        if (adapter != null) adapter.refreshHostDevices();
        loadHostDevices();
        // The master switch is the exception to that: it lives on the rules page, it is not a
        // row, and reading it back cannot lose an edit -- so the warning it drives is refreshed
        // rather than left at what it said when the editor opened.
        refreshUsbEnabled();
    }

    /** Whether the unsaved peripheral rows give the guest a host microphone. */
    public boolean hasMicrophone() {
        for (var peripheral : VMPeripheralConfig.listOf(wrap()))
            for (var endpoint : peripheral.getEndpoints())
                if (endpoint.getMode().isInput()) return true;
        return false;
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        listPeripherals.setItems(
            config.item.opt(VMXhciConfig.KEY_PERIPHERALS, DataItem.newArray()));
        xhciNext = config.item.optLong(VMXhciConfig.KEY_NEXT, 0);
        // VMConfig's constructor already materialized the fixed quartet for configs from
        // before "serial_ports"; ensureDefaults here only covers configs built by hand.
        VMSerialConfig.ensureDefaults(config.item);
        listSerialPorts.setItems(config.item.opt(VMSerialConfig.KEY, DataItem.newArray()));
        // A VM that does not exist yet owns no rules: its id is minted by the save itself, so
        // everything the user adds here is an addition when that id finally exists.
        loadUsbRules(parent.editMode ? config.getId().toString() : null);
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        // Two endpoints pointed at one host device in the same direction would open two AAudio
        // streams onto it: allowed by the platform, but never what someone meant to configure.
        // The direction is part of the identity, so a microphone and a speaker cannot collide.
        // Checked across every card, not within one: two cards aimed at the same speaker is the
        // same mistake as one card aimed at it twice.
        var seen = new ArrayList<String>();
        for (var peripheral : VMPeripheralConfig.listOf(wrap())) {
            if (peripheral.getType() != PeripheralType.VIRTIO_SOUND) continue;
            for (var endpoint : peripheral.getEndpoints()) {
                var key = endpoint.getHostDevice();
                // Unset, or the platform's own routing, can be chosen as often as one likes:
                // it does not name a device, so there is nothing to collide over.
                if (key.isEmpty() || HostAudioDevices.SYSTEM_DEFAULT_KEY.equals(key)) continue;
                var identity = fmt("%s|%s", endpoint.getMode().isInput() ? "in" : "out", key);
                if (seen.contains(identity))
                    return showValidateFailed(R.string.edit_vm_peripheral_duplicate_host);
                seen.add(identity);
            }
        }
        // Nothing checks the controllers: a second one on crosvm, and a port count crosvm does
        // not honour, are both things the VM starts with anyway -- the card says in red what
        // will be ignored, which is not the same as refusing to save it.
        //
        // A path-based serial backend without a path has nowhere to put the bytes. PTY is the
        // exception: its path is an optional convenience symlink. Two USB ACM ports on one
        // slot would be a guaranteed busy-refusal at boot, so it fails here instead.
        var usbSlots = new ArrayList<Integer>();
        for (var iter : requireNonNull(listSerialPorts.getItems())) {
            var port = new VMSerialConfig(iter.getValue());
            var backend = port.getBackend();
            if (backend.usesPath() && backend != SerialBackend.PTY && port.getPath().isEmpty())
                return showValidateFailed(R.string.edit_vm_serial_path_required);
            if (backend == SerialBackend.USB_ACM) {
                if (usbSlots.contains(port.getUsbSlot()))
                    return showValidateFailed(R.string.edit_vm_serial_slot_duplicate);
                usbSlots.add(port.getUsbSlot());
            }
        }
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        config.item.set(VMXhciConfig.KEY_PERIPHERALS,
            requireNonNull(listPeripherals.getItems()));
        config.item.set(VMSerialConfig.KEY, requireNonNull(listSerialPorts.getItems()));
        // Never below what the config already handed out: an id is only safe to mint once.
        config.item.set(VMXhciConfig.KEY_NEXT,
            Math.max(xhciNext, config.item.optLong(VMXhciConfig.KEY_NEXT, 0)));
    }

    // VMPeripheralEditAdapter.XhciHost

    @NonNull
    @Override
    public XhciBindingStore bindings() {
        return bindings;
    }

    @NonNull
    @Override
    public List<UsbHostDeviceInfo> hostDevices() {
        return hostDevices;
    }

    /** The backend as currently selected in the basic tab (before save). */
    @NonNull
    @Override
    public VMBackend backend() {
        try {
            var basic = (VMEditBasicTab) parent.getTab(VMEditTab.TAB_BASIC);
            if (basic != null) return basic.getCurrentBackend();
        } catch (Exception ignored) {
        }
        return VMBackend.DEFAULT;
    }

    @Override
    public boolean usbPassthroughEnabled() {
        return usbEnabled;
    }

    @NonNull
    @Override
    public DataItem createController() {
        // Minted against the rows as they stand, so an id already in the list cannot come out
        // again; the counter moves with it and is written back by saveConfig.
        var scratch = wrap();
        scratch.set(VMXhciConfig.KEY_NEXT, xhciNext);
        var controller = VMXhciConfig.addController(scratch);
        xhciNext = scratch.optLong(VMXhciConfig.KEY_NEXT, xhciNext);
        return controller.item;
    }

    // The USB attach rules: read once here, pushed once by the activity after the save.

    /** Whether this page has bindings the daemon has not been told about. */
    public boolean hasPendingUsbRules() {
        return bindings.isLoaded() && bindings.isDirty();
    }

    /**
     * Folds this page's edits into the rules the daemon holds and pushes the result.
     *
     * <p>Read afresh rather than merged against the copy loaded when the page opened: the
     * global rules page edits the same object, and the whole point of pushing a diff is that
     * neither page eats the other's edits. Called after the VM config was saved, so a rule can
     * name the VM it was written on.</p>
     */
    public void pushUsbRules(@NonNull VMConfig config, @NonNull Runnable onDone,
                             @NonNull Consumer<String> onFailed) {
        if (pushing) return;
        pushing = true;
        var vmId = config.getId().toString();
        introduceVm(config, () -> DaemonConnection.getInstance().buildRequest("usb_rules_get")
            .onResponse(resp -> post(() ->
                mergeAndPush(vmId, resp.optJSONObject("rules"), onDone, onFailed)))
            .onUnsuccessful(resp -> post(() -> fail(message(resp), onFailed)))
            .onError(e -> post(() -> fail(daemonUnavailable(), onFailed)))
            .invoke(), onFailed);
    }

    /**
     * Makes sure the daemon knows the VM whose bindings are about to name it.
     *
     * <p>{@code usb_rules_set} refuses a rule whose target it cannot find, and the daemon's VM
     * store is not vms.json: it is read once at daemon start and learns of a VM when one is
     * created or started. A VM created in this editor would therefore have every binding
     * refused with "VM not found" until it had been started once -- which is the first thing
     * anyone does with this card, so the config goes over first.</p>
     *
     * <p>Only a VM the daemon has never heard of is sent. An existing one is left alone: what
     * would refresh its copy is vm_modify, which builds a new instance and would throw away the
     * console of the run the user is looking at, and a stale copy costs nothing here -- the
     * daemon checks that the VM exists, and a controller it has not heard of yet is skipped when
     * a device is offered rather than refused at save.</p>
     */
    private void introduceVm(@NonNull VMConfig config, @NonNull Runnable then,
                             @NonNull Consumer<String> onFailed) {
        var conn = DaemonConnection.getInstance();
        conn.buildRequest("vm_exists")
            .put("vm_id", config.getId().toString())
            .onResponse(resp -> {
                if (resp.optBoolean("exists", false)) {
                    post(then);
                    return;
                }
                conn.buildRequest("vm_create")
                    .put("config", config)
                    .onResponse(created -> post(then))
                    .onUnsuccessful(created -> post(() -> fail(message(created), onFailed)))
                    .onError(e -> post(() -> fail(daemonUnavailable(), onFailed)))
                    .invoke();
            })
            .onUnsuccessful(resp -> post(() -> fail(message(resp), onFailed)))
            .onError(e -> post(() -> fail(daemonUnavailable(), onFailed)))
            .invoke();
    }

    private void mergeAndPush(@NonNull String vmId, @Nullable JSONObject current,
                              @NonNull Runnable onDone, @NonNull Consumer<String> onFailed) {
        JSONObject payload;
        try {
            payload = new JSONObject();
            payload.put("version", UsbRules.VERSION);
            // The master switch belongs to the rules page; this card only edits rows, so it hands
            // back whatever the daemon just said. Dropping the key is not neutral -- an absent
            // "enabled" reads as on -- so a binding saved while passthrough was off would turn it
            // on and run a full pass, taking back the devices the user had just given to Android.
            payload.put("enabled", current == null || current.optBoolean("enabled", true));
            var live = rowsOf(current);
            // The rows added this session only learn their VM here -- a new VM's id is minted by
            // the save itself -- and they learn it in place, so the snapshot a landed push
            // leaves behind is what the daemon was sent.
            bindings.stampVm(vmId);
            for (var layer : UsbRuleLayer.values()) {
                var merged = XhciBindingDiff.merge(live.get(layer), bindings.snapshot(layer),
                    bindings.desired(layer), row -> bindings.owns(row, vmId));
                payload.put(layer.key, jsonOf(merged));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to build the USB rules payload", e);
            fail(String.valueOf(e.getMessage()), onFailed);
            return;
        }
        DaemonConnection.getInstance().buildRequest("usb_rules_set")
            .put("rules", payload)
            .onResponse(resp -> post(() -> {
                pushing = false;
                bindings.markSaved();
                onDone.run();
            }))
            .onUnsuccessful(resp -> post(() -> fail(message(resp), onFailed)))
            .onError(e -> post(() -> fail(daemonUnavailable(), onFailed)))
            .invoke();
    }

    private void fail(@NonNull String message, @NonNull Consumer<String> onFailed) {
        pushing = false;
        onFailed.accept(message);
    }

    @NonNull
    private String daemonUnavailable() {
        return parent.getString(R.string.usb_rules_daemon_unavailable);
    }

    /** The rules as they are now; nothing is loaded twice, so this runs once per edit session. */
    private void loadUsbRules(@Nullable String vmId) {
        var controllers = new ArrayList<String>();
        for (var controller : VMXhciConfig.listControllers(wrap())) {
            var id = controller.getControllerId();
            if (!id.isEmpty()) controllers.add(id);
        }
        DaemonConnection.getInstance().buildRequest("usb_rules_get")
            .onResponse(resp -> post(() -> {
                var rules = resp.optJSONObject("rules");
                usbEnabled = rules == null || rules.optBoolean("enabled", true);
                bindings.load(rowsOf(rules), controllers, vmId);
                if (adapter != null) adapter.refreshBindings();
            }))
            // A page that could not read the rules pushes nothing at save: the cards say the
            // daemon is not there and refuse to add, which is better than a save that would
            // truncate every rule the user has.
            .onUnsuccessful(resp -> Log.w(TAG, fmt("usb_rules_get: %s", message(resp))))
            .onError(e -> Log.w(TAG, "usb_rules_get failed", e))
            .invoke();
    }

    /**
     * The master switch alone, for a tab that is being shown again: the rules page may have been
     * visited in between. Nothing else of the response is used -- the rows on this page are the
     * ones the user has been editing, and a re-read would throw them away.
     */
    private void refreshUsbEnabled() {
        DaemonConnection.getInstance().buildRequest("usb_rules_get")
            .onResponse(resp -> post(() -> {
                var rules = resp.optJSONObject("rules");
                boolean enabled = rules == null || rules.optBoolean("enabled", true);
                if (enabled == usbEnabled) return;
                usbEnabled = enabled;
                if (adapter != null) adapter.refreshBindings();
            }))
            .onUnsuccessful(resp -> Log.w(TAG, fmt("usb_rules_get: %s", message(resp))))
            .onError(e -> Log.w(TAG, "usb_rules_get failed", e))
            .invoke();
    }

    private void loadHostDevices() {
        DaemonConnection.getInstance().buildRequest("usb_host_list")
            .onResponse(resp -> post(() -> {
                hostDevices.clear();
                hostDevices.addAll(UsbHostDeviceInfo.fromArray(resp.optJSONArray("devices")));
                if (adapter != null) adapter.refreshBindings();
            }))
            .onUnsuccessful(resp -> Log.w(TAG, fmt("usb_host_list: %s", message(resp))))
            .onError(e -> Log.w(TAG, "usb_host_list failed", e))
            .invoke();
    }

    /** Every layer's rules, in the wire order the daemon sent them. */
    @NonNull
    private static Map<UsbRuleLayer, List<Row>> rowsOf(@Nullable JSONObject rules) {
        var out = new EnumMap<UsbRuleLayer, List<Row>>(UsbRuleLayer.class);
        for (var layer : UsbRuleLayer.values()) {
            var rows = new ArrayList<Row>();
            var arr = rules == null ? null : rules.optJSONArray(layer.key);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                var obj = arr.optJSONObject(i);
                // An entry that is not an object is one the daemon would refuse anyway; keeping
                // it would only put the refusal on this page's save.
                if (obj != null) rows.add(new Row(text(obj, "id"), text(obj, "port"),
                    text(obj, "vm"), text(obj, "controller"), targetOf(obj)));
            }
            out.put(layer, rows);
        }
        return out;
    }

    /**
     * What a rule does with what it matches. A row carrying no target is one written before
     * there was a target to write -- the dev phone's own file -- and its vm is the whole of what
     * it said, which is how the daemon reads it too.
     */
    @NonNull
    private static String targetOf(@NonNull JSONObject obj) {
        var target = text(obj, "target");
        if (target != null) return target;
        return text(obj, "vm") == null ? "host" : XhciBindingDiff.TARGET_VM;
    }

    /** A string field, where an absent key, a JSON null and "" all read as null. */
    @Nullable
    private static String text(@NonNull JSONObject obj, @NonNull String key) {
        if (!obj.has(key) || obj.isNull(key)) return null;
        var value = obj.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    /** The wire form: a field the rule does not carry is left out, which reads back as null. */
    @NonNull
    private static JSONArray jsonOf(@NonNull List<Row> rows) throws JSONException {
        var arr = new JSONArray();
        for (var row : rows) {
            var obj = new JSONObject();
            if (row.id != null) obj.put("id", row.id);
            if (row.port != null) obj.put("port", row.port);
            if (row.vm != null) obj.put("vm", row.vm);
            if (row.controller != null) obj.put("controller", row.controller);
            // Always written, the way the daemon writes it: a row this page is only passing
            // through says what it always said, and a reader that had to infer the outcome from
            // the other fields would be a second answer to a question this key answers.
            obj.put("target", row.target);
            arr.put(obj);
        }
        return arr;
    }

    @NonNull
    private String message(@NonNull JSONObject resp) {
        var text = resp.optString("message", "");
        return text.isEmpty() ? parent.getString(R.string.usb_rules_request_failed) : text;
    }

    /** Runs on the main thread, unless the editor is already going away. */
    private void post(@NonNull Runnable task) {
        mainHandler.post(() -> {
            if (parent.isFinishing() || parent.isDestroyed()) return;
            task.run();
        });
    }

    /** The unsaved rows, shaped like a VM config so {@link VMPeripheralConfig} can read them. */
    @NonNull
    private DataItem wrap() {
        var wrapper = DataItem.newObject();
        wrapper.set(VMXhciConfig.KEY_PERIPHERALS, requireNonNull(listPeripherals.getItems()));
        return wrapper;
    }
}
