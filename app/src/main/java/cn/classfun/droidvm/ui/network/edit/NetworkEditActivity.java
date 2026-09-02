// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.network.edit;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.NetUtils.generateRandomMac;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.daemon.network.backend.UplinkResolver;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkConfigValidator;
import cn.classfun.droidvm.lib.store.network.NetworkConflicts;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.ui.BackAskHelper;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.ui.network.NetworkActions;
import cn.classfun.droidvm.ui.network.NetworkPresets;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class NetworkEditActivity extends AppCompatActivity {
    public static final String EXTRA_NETWORK_ID = "network_id";
    /** Interface-name charset: ASCII letters, digits, hyphen, underscore. */
    private static final InputFilter BRIDGE_NAME_CHARSET = (src, start, end, dst, ds, de) ->
        src.subSequence(start, end).toString().matches("[A-Za-z0-9_-]*") ? null : "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<VlanConfig> vlans = new ArrayList<>();
    private final List<VlanCardBinder> binders = new ArrayList<>();
    // parallel uplink picker entries: display label, stored value (logical id
    // or literal name) and whether that uplink can be enslaved into a bridge
    private final List<String> uplinkLabels = new ArrayList<>();
    private final List<String> uplinkValues = new ArrayList<>();
    private final List<Boolean> uplinkBridgeable = new ArrayList<>();
    private String selectedUplink = UplinkResolver.ID_WIFI;
    private boolean editMode = false;
    private UUID editNetworkId = null;
    private CollapsingToolbarLayout collapsingToolbar;
    private TextView tvRunningBanner;
    private TextInputRowWidget inputName;
    private TextInputRowWidget inputBridge;
    private SwitchRowWidget swAutoUp, swStp;
    private DropdownRowWidget ddBridgeType, ddUplinkMode;
    private DropdownRowWidget ddL2Uplink;
    private SwitchRowWidget swPseudoBridge;
    private TextView tvPseudoHint;
    private LinearLayout groupL2, groupL3;
    private TextInputRowWidget inputMac;
    private LinearLayout containerVlans;
    private TextView tvVlanEmpty;
    private MaterialButton btnAddVlan;
    private FloatingActionButton fab;
    private NetworkStore store;
    @SuppressWarnings("FieldCanBeLocal")
    private String[] bridgeTypeLabels;
    @SuppressWarnings("FieldCanBeLocal")
    private String[] uplinkModeLabels;
    private BridgeType bridgeType = BridgeType.LINUX;
    private UplinkMode uplinkMode = UplinkMode.L3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_edit);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        tvRunningBanner = findViewById(R.id.tv_running_banner);
        inputName = findViewById(R.id.input_name);
        inputBridge = findViewById(R.id.input_bridge);
        swAutoUp = findViewById(R.id.sw_auto_up);
        swStp = findViewById(R.id.sw_stp);
        ddBridgeType = findViewById(R.id.dd_bridge_type);
        ddUplinkMode = findViewById(R.id.dd_uplink_mode);
        groupL2 = findViewById(R.id.group_l2);
        ddL2Uplink = findViewById(R.id.dd_l2_uplink);
        swPseudoBridge = findViewById(R.id.sw_pseudo_bridge);
        tvPseudoHint = findViewById(R.id.tv_pseudo_hint);
        groupL3 = findViewById(R.id.group_l3);
        inputMac = findViewById(R.id.input_mac);
        containerVlans = findViewById(R.id.container_vlans);
        tvVlanEmpty = findViewById(R.id.tv_vlan_empty);
        btnAddVlan = findViewById(R.id.btn_add_vlan);
        fab = findViewById(R.id.fab_save);
        initialize();
    }

    private void initialize() {
        new BackAskHelper(this);
        store = new NetworkStore();
        store.load(this);
        bridgeTypeLabels = new String[]{
            getString(R.string.network_edit_bridge_type_linux),
            getString(R.string.network_edit_bridge_type_gvisor),
        };
        uplinkModeLabels = new String[]{
            getString(R.string.network_edit_uplink_l2),
            getString(R.string.network_edit_uplink_l3),
        };
        ddBridgeType.setAdapter(IconItemAdapter.create(
            this, bridgeTypeLabels, R.drawable.ic_switch));
        ddBridgeType.setOnItemClickListener((p, v, pos, id) -> {
            bridgeType = pos == 1 ? BridgeType.GVISOR : BridgeType.LINUX;
            onBridgeTypeChanged();
        });
        ddL2Uplink.setOnItemClickListener((p, v, pos, id) -> onUplinkSelected(pos));
        btnAddVlan.setOnClickListener(v -> onAddVlan());
        inputMac.setEndIconOnClickListener(v -> inputMac.setText(generateRandomMac()));
        inputBridge.setFilters(
            new InputFilter.LengthFilter(NetworkPresets.MAX_BRIDGE_NAME_LEN),
            BRIDGE_NAME_CHARSET
        );
        fab.setOnClickListener(v -> onSaveClicked());
        updateUplinkModeDropdown();
        loadUplinks();
        var intent = getIntent();
        if (intent != null && intent.hasExtra(EXTRA_NETWORK_ID)) {
            editMode = true;
            editNetworkId = UUID.fromString(intent.getStringExtra(EXTRA_NETWORK_ID));
            collapsingToolbar.setTitle(getString(R.string.network_edit_title));
            loadExistingConfig();
            checkRunningState();
        } else {
            collapsingToolbar.setTitle(getString(R.string.network_create_title));
            generateDefaults();
        }
        ddBridgeType.setText(
            bridgeType == BridgeType.GVISOR ? bridgeTypeLabels[1] : bridgeTypeLabels[0]);
        applyUplinkMode();
        rebuildVlanCards();
    }

    private void updateUplinkModeDropdown() {
        // L2 bridging is Linux-only; "none" is just L3 with zero VLANs
        var labels = bridgeType == BridgeType.GVISOR
            ? new String[]{uplinkModeLabels[1]}
            : uplinkModeLabels;
        ddUplinkMode.setAdapter(IconItemAdapter.create(
            this, labels, R.drawable.ic_web_plus));
        ddUplinkMode.setOnItemClickListener((p, v, pos, id) -> {
            uplinkMode = labels[pos].equals(uplinkModeLabels[0])
                ? UplinkMode.L2 : UplinkMode.L3;
            applyUplinkMode();
        });
        ddUplinkMode.setText(labelOfMode(uplinkMode));
    }

    @NonNull
    private String labelOfMode(@NonNull UplinkMode mode) {
        return mode == UplinkMode.L2 ? uplinkModeLabels[0] : uplinkModeLabels[1];
    }

    private void onBridgeTypeChanged() {
        if (bridgeType == BridgeType.GVISOR && uplinkMode == UplinkMode.L2)
            uplinkMode = UplinkMode.L3;
        updateUplinkModeDropdown();
        applyUplinkMode();
        for (var binder : binders)
            binder.applyBridgeType(bridgeType);
    }

    private void applyUplinkMode() {
        groupL2.setVisibility(uplinkMode == UplinkMode.L2 ? VISIBLE : GONE);
        groupL3.setVisibility(uplinkMode == UplinkMode.L3 ? VISIBLE : GONE);
        updatePseudoHint();
    }

    private void loadUplinks() {
        rebuildUplinks(null);
        DaemonConnection.getInstance().buildRequest("network_list_uplinks")
            .onResponse(resp -> {
                var data = resp.optJSONObject("data");
                mainHandler.post(() -> {
                    rebuildUplinks(data);
                    for (var binder : binders)
                        binder.applyBridgeType(bridgeType);
                });
            })
            .onUnsuccessful(r -> {
            })
            .onError(e -> {
            })
            .invoke();
    }

    /**
     * Rebuilds the picker entries: the three logical identifiers (always shown,
     * with their live-resolved name or "unavailable") followed by the concrete
     * physical L2 devices. The current selection is preserved.
     */
    private void rebuildUplinks(@Nullable JSONObject data) {
        uplinkLabels.clear();
        uplinkValues.clear();
        uplinkBridgeable.clear();
        addIdentifier(UplinkResolver.ID_WIFI,
            getString(R.string.network_edit_uplink_wifi), false, data);
        addIdentifier(UplinkResolver.ID_ETHERNET,
            getString(R.string.network_edit_uplink_ethernet), true, data);
        addIdentifier(UplinkResolver.ID_TETHERING,
            getString(R.string.network_edit_uplink_tethering), true, data);
        if (data != null) {
            var devices = data.optJSONArray("devices");
            if (devices != null)
                for (int i = 0; i < devices.length(); i++) {
                    var obj = devices.optJSONObject(i);
                    if (obj == null) continue;
                    var name = obj.optString("name", "");
                    if (name.isEmpty() || uplinkValues.contains(name)) continue;
                    uplinkLabels.add(name);
                    uplinkValues.add(name);
                    uplinkBridgeable.add(obj.optBoolean("bridgeable", true));
                }
        }
        // keep a saved literal device that isn't currently present selectable
        if (selectedUplink != null && !selectedUplink.isEmpty()
            && !uplinkValues.contains(selectedUplink)) {
            uplinkLabels.add(getString(R.string.network_edit_uplink_identifier,
                selectedUplink, getString(R.string.network_edit_uplink_unavailable)));
            uplinkValues.add(selectedUplink);
            uplinkBridgeable.add(true);
        }
        updateUplinkDropdown();
    }

    private void addIdentifier(
        @NonNull String id, @NonNull String display, boolean bridgeable,
        @Nullable JSONObject data
    ) {
        String resolved = "";
        boolean br = bridgeable;
        if (data != null) {
            var ids = data.optJSONArray("identifiers");
            if (ids != null)
                for (int i = 0; i < ids.length(); i++) {
                    var obj = ids.optJSONObject(i);
                    if (obj != null && id.equals(obj.optString("id"))) {
                        resolved = obj.optString("name", "");
                        br = obj.optBoolean("bridgeable", bridgeable);
                        break;
                    }
                }
        }
        var shown = resolved.isEmpty()
            ? getString(R.string.network_edit_uplink_unavailable) : resolved;
        uplinkLabels.add(getString(R.string.network_edit_uplink_identifier, display, shown));
        uplinkValues.add(id);
        uplinkBridgeable.add(br);
    }

    private void updateUplinkDropdown() {
        ddL2Uplink.setAdapter(IconItemAdapter.create(
            this, uplinkLabels.toArray(new String[0]), R.drawable.ic_ethernet));
        if (uplinkLabels.isEmpty()) return;
        int idx = uplinkValues.indexOf(selectedUplink);
        if (idx < 0) idx = 0;
        selectedUplink = uplinkValues.get(idx);
        ddL2Uplink.setText(uplinkLabels.get(idx));
        applyPseudoLock(uplinkBridgeable.get(idx));
    }

    private void onUplinkSelected(int pos) {
        if (pos < 0 || pos >= uplinkValues.size()) return;
        selectedUplink = uplinkValues.get(pos);
        boolean bridgeable = uplinkBridgeable.get(pos);
        // re-evaluate the toggle once on selection: bridgeable defaults on
        if (bridgeable) swPseudoBridge.setChecked(true);
        applyPseudoLock(bridgeable);
    }

    /**
     * Sets the pseudo-bridge toggle's locked state for an uplink: a
     * non-bridgeable uplink (Wi-Fi STA / non-ethernet) forces it on and locks
     * it; a bridgeable one leaves it user-controllable.
     */
    private void applyPseudoLock(boolean bridgeable) {
        if (!bridgeable) swPseudoBridge.setChecked(true);
        swPseudoBridge.setSwitchEnabled(bridgeable);
        updatePseudoHint();
    }

    private void updatePseudoHint() {
        if (uplinkMode != UplinkMode.L2) return;
        int idx = uplinkValues.indexOf(selectedUplink);
        boolean bridgeable = idx >= 0 && idx < uplinkBridgeable.size()
            ? uplinkBridgeable.get(idx) : true;
        tvPseudoHint.setText(getString(bridgeable
            ? R.string.network_edit_pseudo_hint_wired
            : R.string.network_edit_pseudo_hint_wifi));
    }

    private void generateDefaults() {
        bridgeType = BridgeType.LINUX;
        uplinkMode = UplinkMode.L3;
        ddUplinkMode.setText(labelOfMode(uplinkMode));
        inputMac.setText(generateRandomMac());
        // suggest "br" + 8 UUID hex (10 chars, within the 12 cap); user may edit
        inputBridge.setText(fmt("br%s", UUID.randomUUID().toString().substring(0, 8)));
        vlans.add(newVlan(0));
    }

    /** A new VLAN entry with paired random networks (empty when exhausted). */
    @NonNull
    private VlanConfig newVlan(int vlanId) {
        return NetworkPresets.newVlan(vlanId, bridgeType, generatePairedCidrs());
    }

    /**
     * A free subnet pair, avoiding every other network and this network's own
     * VLAN cards. Null when nothing in the pool fits.
     */
    @Nullable
    private String[] generatePairedCidrs() {
        var used4 = new ArrayList<IPv4Network>();
        var used6 = new ArrayList<IPv6Network>();
        storeAllBinders();
        NetworkConflicts.collectSubnets(vlans, used4, used6);
        NetworkPresets.collectStoreNetworks(store, editNetworkId, bridgeType, used4, used6);
        return NetworkPresets.pickFreeCidrPair(used4, used6);
    }

    private void loadExistingConfig() {
        var config = store.findById(editNetworkId);
        if (config == null) {
            Toast.makeText(this, R.string.network_edit_error_not_found, LENGTH_LONG).show();
            finish();
            return;
        }
        inputName.setText(config.getName());
        swAutoUp.setChecked(config.isAutoUp());
        swStp.setChecked(config.isStp());
        bridgeType = config.getBridgeType();
        uplinkMode = config.getUplinkMode();
        inputBridge.setText(config.getBridgeName());
        ddUplinkMode.setText(labelOfMode(uplinkMode));
        var l2Uplink = config.getL2Uplink();
        if (l2Uplink != null && !l2Uplink.isEmpty()) selectedUplink = l2Uplink;
        swPseudoBridge.setChecked(config.isL2PseudoBridge());
        rebuildUplinks(null);   // reflect the loaded selection; refreshed by IPC
        var l3Mac = config.l3().optString("mac_address", "");
        inputMac.setText(l3Mac == null || l3Mac.isEmpty() ? generateRandomMac() : l3Mac);
        vlans.clear();
        for (var vlan : config.getVlans()) {
            // deep copy so cancel doesn't mutate the store
            var copy = DataItem.newObject();
            copy.puts(vlan.item);
            vlans.add(new VlanConfig(copy));
        }
    }

    /** Editing a RUNNING network is not allowed; show a banner and block save. */
    private void checkRunningState() {
        DaemonConnection.getInstance().buildRequest("network_status")
            .put("network_id", editNetworkId.toString())
            .onResponse(resp -> {
                var state = resp.optString("state", "");
                if (state.equalsIgnoreCase("running")
                    || state.equalsIgnoreCase("starting")) {
                    mainHandler.post(() -> {
                        tvRunningBanner.setVisibility(VISIBLE);
                        fab.setEnabled(false);
                    });
                }
            })
            .onUnsuccessful(r -> {
            })
            .onError(e -> {
            })
            .invoke();
    }

    private void onAddVlan() {
        storeAllBinders();
        // smallest free id in 0, 10, 20, ...
        var used = new HashSet<Integer>();
        for (var vlan : vlans) used.add(vlan.getVlanId());
        int id = 0;
        while (used.contains(id) && id < 4090) id += 10;
        vlans.add(newVlan(id));
        rebuildVlanCards();
    }

    private void rebuildVlanCards() {
        storeAllBinders();
        containerVlans.removeAllViews();
        binders.clear();
        tvVlanEmpty.setVisibility(vlans.isEmpty() ? VISIBLE : GONE);
        var inflater = LayoutInflater.from(this);
        for (int i = 0; i < vlans.size(); i++) {
            final int idx = i;
            var view = inflater.inflate(R.layout.item_network_vlan, containerVlans, false);
            var binder = new VlanCardBinder(view, uplinkValues);
            binder.bind(vlans.get(i), bridgeType);
            binder.delete.setOnClickListener(v -> {
                vlans.remove(idx);
                rebuildVlanCards();
            });
            binders.add(binder);
            containerVlans.addView(view);
        }
    }

    private void storeAllBinders() {
        for (int i = 0; i < binders.size() && i < vlans.size(); i++)
            binders.get(i).store(vlans.get(i));
    }

    private void onSaveClicked() {
        storeAllBinders();
        var name = inputName.getText().trim();
        if (name.isEmpty()) {
            inputName.setError(getString(R.string.network_edit_error_name_empty));
            return;
        }
        inputName.setError(null);
        if (!store.isNameUnique(name, editNetworkId)) {
            inputName.setError(getString(R.string.network_edit_error_name_duplicate));
            return;
        }
        var bridgeName = inputBridge.getText().trim();
        if (bridgeName.isEmpty()) {
            inputBridge.setError(getString(R.string.network_edit_error_bridge_empty));
            return;
        }
        if (!bridgeName.matches("[a-zA-Z][a-zA-Z0-9_-]*")
            || bridgeName.length() > NetworkPresets.MAX_BRIDGE_NAME_LEN) {
            inputBridge.setError(getString(R.string.network_edit_error_bridge_invalid));
            return;
        }
        inputBridge.setError(null);
        if (!store.isBridgeNameUnique(bridgeName, editNetworkId)) {
            inputBridge.setError(getString(R.string.network_edit_error_bridge_duplicate));
            return;
        }
        var config = new NetworkConfig();
        if (editMode && editNetworkId != null)
            config.setId(editNetworkId);
        config.setName(name);
        config.setBridgeName(bridgeName);
        config.item.set("auto_up", swAutoUp.isChecked());
        config.item.set("stp", swStp.isChecked());
        config.setBridgeType(bridgeType);
        config.setUplinkMode(uplinkMode);
        if (uplinkMode == UplinkMode.L2) {
            config.l2().set("uplink", selectedUplink);
            config.l2().set("pseudo_bridge", swPseudoBridge.isChecked());
        } else {
            config.l3().set("mac_address", inputMac.getText().trim());
            var arr = DataItem.newArray();
            for (var vlan : vlans) arr.append(vlan.item);
            config.l3().set("vlans", arr);
        }
        try {
            NetworkConfigValidator.validate(config);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), LENGTH_LONG).show();
            return;
        }
        var conflict = checkConflicts(config);
        if (conflict != null) {
            Toast.makeText(this, conflict, LENGTH_LONG).show();
            return;
        }
        if (editMode) {
            store.update(config);
        } else {
            store.add(config);
        }
        store.save(this);
        NetworkActions.syncToDaemon(config);
        Toast.makeText(this,
            editMode ? getString(R.string.network_edit_saved, name) :
                getString(R.string.network_create_success, name),
            LENGTH_SHORT).show();
        finish();
    }

    /**
     * The reason this config cannot be saved alongside the others, or null when it can. Only
     * networks of the same kind are consulted -- see {@link NetworkConflicts} for why a Linux
     * bridge and a gVisor network are free to hold the same prefix.
     */
    @Nullable
    private String checkConflicts(@NonNull NetworkConfig config) {
        var self = NetworkConflicts.findSelfOverlap(config);
        if (self != null)
            return getString(R.string.network_edit_error_self_overlap, self[0], self[1]);
        var conflict = NetworkConflicts.find(config, store, editNetworkId);
        if (conflict == null) return null;
        switch (conflict.kind) {
            case IPV4:
                return getString(R.string.network_edit_error_ipv4_overlap,
                    conflict.mine, conflict.otherName(), conflict.theirs);
            case IPV6:
                return getString(R.string.network_edit_error_ipv6_overlap,
                    conflict.mine, conflict.otherName(), conflict.theirs);
            default:
                return getString(R.string.network_edit_error_uplink_taken,
                    conflict.mine, conflict.otherName());
        }
    }
}
