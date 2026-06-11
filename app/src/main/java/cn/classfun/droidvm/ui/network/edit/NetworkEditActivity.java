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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.network.IPv4Address;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkConfigValidator;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.ui.BackAskHelper;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class NetworkEditActivity extends AppCompatActivity {
    public static final String EXTRA_NETWORK_ID = "network_id";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<VlanConfig> vlans = new ArrayList<>();
    private final List<VlanCardBinder> binders = new ArrayList<>();
    private final List<String> uplinkNames = new ArrayList<>();
    private boolean editMode = false;
    private UUID editNetworkId = null;
    private String existingBridgeName = null;
    private CollapsingToolbarLayout collapsingToolbar;
    private TextView tvRunningBanner;
    private TextInputRowWidget inputName;
    private SwitchRowWidget swAutoUp, swStp;
    private DropdownRowWidget ddBridgeType, ddUplinkMode;
    private DropdownRowWidget ddL2Uplink, ddPseudoBridge;
    private TextView tvPseudoHint;
    private LinearLayout groupL2, groupL3, groupNone;
    private TextInputRowWidget inputMac, inputMacNone;
    private LinearLayout containerVlans;
    private MaterialButton btnAddVlan;
    private FloatingActionButton fab;
    private NetworkStore store;
    private String[] bridgeTypeLabels;
    private String[] uplinkModeLabels;
    private String[] pseudoLabels;
    private BridgeType bridgeType = BridgeType.LINUX;
    private UplinkMode uplinkMode = UplinkMode.L3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_edit);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        tvRunningBanner = findViewById(R.id.tv_running_banner);
        inputName = findViewById(R.id.input_name);
        swAutoUp = findViewById(R.id.sw_auto_up);
        swStp = findViewById(R.id.sw_stp);
        ddBridgeType = findViewById(R.id.dd_bridge_type);
        ddUplinkMode = findViewById(R.id.dd_uplink_mode);
        groupL2 = findViewById(R.id.group_l2);
        ddL2Uplink = findViewById(R.id.dd_l2_uplink);
        ddPseudoBridge = findViewById(R.id.dd_pseudo_bridge);
        tvPseudoHint = findViewById(R.id.tv_pseudo_hint);
        groupL3 = findViewById(R.id.group_l3);
        inputMac = findViewById(R.id.input_mac);
        containerVlans = findViewById(R.id.container_vlans);
        btnAddVlan = findViewById(R.id.btn_add_vlan);
        groupNone = findViewById(R.id.group_none);
        inputMacNone = findViewById(R.id.input_mac_none);
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
            getString(R.string.network_edit_uplink_none),
        };
        pseudoLabels = new String[]{
            getString(R.string.network_edit_pseudo_auto),
            getString(R.string.network_edit_pseudo_on),
            getString(R.string.network_edit_pseudo_off),
        };
        ddBridgeType.setAdapter(IconItemAdapter.create(
            this, bridgeTypeLabels, R.drawable.ic_switch));
        ddBridgeType.setOnItemClickListener((p, v, pos, id) -> {
            bridgeType = pos == 1 ? BridgeType.GVISOR : BridgeType.LINUX;
            onBridgeTypeChanged();
        });
        ddPseudoBridge.setAdapter(IconItemAdapter.create(
            this, pseudoLabels, R.drawable.ic_connection));
        ddPseudoBridge.setText(pseudoLabels[0]);
        ddL2Uplink.setOnItemClickListener((p, v, pos, id) -> updatePseudoHint());
        btnAddVlan.setOnClickListener(v -> onAddVlan());
        inputMac.setEndIconOnClickListener(v -> inputMac.setText(generateRandomMac()));
        inputMacNone.setEndIconOnClickListener(v -> inputMacNone.setText(generateRandomMac()));
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
        // L2 bridging is Linux-only
        var labels = bridgeType == BridgeType.GVISOR
            ? new String[]{uplinkModeLabels[1], uplinkModeLabels[2]}
            : uplinkModeLabels;
        ddUplinkMode.setAdapter(IconItemAdapter.create(
            this, labels, R.drawable.ic_web_plus));
        ddUplinkMode.setOnItemClickListener((p, v, pos, id) -> {
            var label = labels[pos];
            if (label.equals(uplinkModeLabels[0])) uplinkMode = UplinkMode.L2;
            else if (label.equals(uplinkModeLabels[1])) uplinkMode = UplinkMode.L3;
            else uplinkMode = UplinkMode.NONE;
            applyUplinkMode();
        });
        ddUplinkMode.setText(labelOfMode(uplinkMode));
    }

    @NonNull
    private String labelOfMode(@NonNull UplinkMode mode) {
        switch (mode) {
            case L2:
                return uplinkModeLabels[0];
            case L3:
                return uplinkModeLabels[1];
            default:
                return uplinkModeLabels[2];
        }
    }

    private void onBridgeTypeChanged() {
        if (bridgeType == BridgeType.GVISOR && uplinkMode == UplinkMode.L2) {
            uplinkMode = UplinkMode.L3;
            if (vlans.isEmpty()) addDefaultVlan();
        }
        updateUplinkModeDropdown();
        applyUplinkMode();
        for (var binder : binders)
            binder.applyBridgeType(bridgeType);
    }

    private void applyUplinkMode() {
        groupL2.setVisibility(uplinkMode == UplinkMode.L2 ? VISIBLE : GONE);
        groupL3.setVisibility(uplinkMode == UplinkMode.L3 ? VISIBLE : GONE);
        groupNone.setVisibility(uplinkMode == UplinkMode.NONE ? VISIBLE : GONE);
        if (uplinkMode == UplinkMode.L3 && vlans.isEmpty()) {
            addDefaultVlan();
            rebuildVlanCards();
        }
        updatePseudoHint();
    }

    private void loadUplinks() {
        uplinkNames.clear();
        uplinkNames.add("WiFi");
        uplinkNames.add("Ethernet");
        updateUplinkDropdown();
        DaemonConnection.getInstance().buildRequest("network_list_uplinks")
            .onResponse(resp -> {
                var data = resp.optJSONArray("data");
                if (data == null) return;
                var names = new ArrayList<String>();
                names.add("WiFi");
                names.add("Ethernet");
                for (int i = 0; i < data.length(); i++) {
                    var obj = data.optJSONObject(i);
                    if (obj == null) continue;
                    var name = obj.optString("name", "");
                    if (!name.isEmpty() && !names.contains(name)) names.add(name);
                }
                mainHandler.post(() -> {
                    uplinkNames.clear();
                    uplinkNames.addAll(names);
                    updateUplinkDropdown();
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

    private void updateUplinkDropdown() {
        var current = ddL2Uplink.getText();
        ddL2Uplink.setAdapter(IconItemAdapter.create(
            this, uplinkNames.toArray(new String[0]), R.drawable.ic_ethernet));
        if (!current.isEmpty()) ddL2Uplink.setText(current);
        else ddL2Uplink.setText("WiFi");
        updatePseudoHint();
    }

    private void updatePseudoHint() {
        if (uplinkMode != UplinkMode.L2) return;
        var uplink = ddL2Uplink.getText();
        boolean wifi = uplink.equalsIgnoreCase("WiFi")
            || uplink.toLowerCase().startsWith("wlan");
        tvPseudoHint.setText(getString(
            wifi ? R.string.network_edit_pseudo_hint_wifi
                : R.string.network_edit_pseudo_hint_wired));
    }

    private void generateDefaults() {
        bridgeType = BridgeType.LINUX;
        uplinkMode = UplinkMode.L3;
        ddUplinkMode.setText(labelOfMode(uplinkMode));
        inputMac.setText(generateRandomMac());
        inputMacNone.setText(generateRandomMac());
        addDefaultVlan();
    }

    private void addDefaultVlan() {
        var vlan = VlanConfig.createDefault(0);
        vlan.ipv4().set("cidr", generateRandomIPv4().toString());
        vlans.add(vlan);
    }

    @NonNull
    private IPv4Network generateRandomIPv4() {
        var existing = collectOtherIPv4Networks();
        for (int attempt = 0; attempt < 200; attempt++) {
            int b = 180 + random.nextInt(10);  // 180-189
            int c = random.nextInt(256);       // 0-255
            var candidate = new IPv4Network(new IPv4Address(10, b, c, 1), 24);
            boolean conflicts = false;
            for (var ex : existing) {
                if (candidate.overlaps(ex)) {
                    conflicts = true;
                    break;
                }
            }
            if (!conflicts) return candidate;
        }
        return new IPv4Network(new IPv4Address(10, 180, 0, 1), 24);
    }

    @NonNull
    private List<IPv4Network> collectOtherIPv4Networks() {
        var out = new ArrayList<IPv4Network>();
        store.forEach((id, cfg) -> {
            if (id.equals(editNetworkId)) return;
            for (var vlan : cfg.getVlans()) {
                var net = vlan.getIpv4Network();
                if (net != null) out.add(net);
                for (var cidr : vlan.getIpv4Secondary()) {
                    try {
                        out.add(IPv4Network.parse(cidr));
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        return out;
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
        existingBridgeName = config.getBridgeName();
        ddUplinkMode.setText(labelOfMode(uplinkMode));
        var l2Uplink = config.getL2Uplink();
        if (l2Uplink != null) ddL2Uplink.setText(l2Uplink);
        var pseudo = config.getL2PseudoBridge();
        ddPseudoBridge.setText(
            pseudo.equals("on") ? pseudoLabels[1]
                : pseudo.equals("off") ? pseudoLabels[2] : pseudoLabels[0]);
        var l3Mac = config.l3().optString("mac_address", "");
        if (!l3Mac.isEmpty()) inputMac.setText(l3Mac);
        else inputMac.setText(generateRandomMac());
        var noneMac = config.none().optString("mac_address", "");
        if (!noneMac.isEmpty()) inputMacNone.setText(noneMac);
        else inputMacNone.setText(generateRandomMac());
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
        int nextId = 0;
        for (var vlan : vlans)
            nextId = Math.max(nextId, vlan.getVlanId());
        // suggest the next free tagged id (untagged 0 exists by default)
        var vlan = VlanConfig.createDefault(Math.min(nextId + 10, 4094));
        vlans.add(vlan);
        rebuildVlanCards();
    }

    private void rebuildVlanCards() {
        storeAllBinders();
        containerVlans.removeAllViews();
        binders.clear();
        var inflater = LayoutInflater.from(this);
        for (int i = 0; i < vlans.size(); i++) {
            final int idx = i;
            var view = inflater.inflate(R.layout.item_network_vlan, containerVlans, false);
            var binder = new VlanCardBinder(view, uplinkNames);
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
        var config = new NetworkConfig();
        if (editMode && editNetworkId != null)
            config.setId(editNetworkId);
        config.setName(name);
        config.setBridgeName(existingBridgeName != null
            ? existingBridgeName
            : fmt("br%s", config.getId().toString().substring(0, 8).replace("-", "")));
        config.item.set("auto_up", swAutoUp.isChecked());
        config.item.set("stp", swStp.isChecked());
        config.setBridgeType(bridgeType);
        config.setUplinkMode(uplinkMode);
        switch (uplinkMode) {
            case L2: {
                config.l2().set("uplink", ddL2Uplink.getText().trim());
                var pseudoText = ddPseudoBridge.getText();
                String pseudo = "auto";
                if (pseudoText.equals(pseudoLabels[1])) pseudo = "on";
                else if (pseudoText.equals(pseudoLabels[2])) pseudo = "off";
                config.l2().set("pseudo_bridge", pseudo);
                break;
            }
            case L3: {
                config.l3().set("mac_address", inputMac.getText().trim());
                var arr = DataItem.newArray();
                for (var vlan : vlans) arr.append(vlan.item);
                config.l3().set("vlans", arr);
                break;
            }
            case NONE:
                config.none().set("mac_address", inputMacNone.getText().trim());
                break;
        }
        try {
            NetworkConfigValidator.validate(config);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), LENGTH_LONG).show();
            return;
        }
        var overlap = checkOverlaps(config);
        if (overlap != null) {
            Toast.makeText(this, overlap, LENGTH_LONG).show();
            return;
        }
        if (editMode) {
            store.update(config);
        } else {
            store.add(config);
        }
        store.save(this);
        syncToDaemon(config);
        Toast.makeText(this,
            editMode ? getString(R.string.network_edit_saved, name) :
                getString(R.string.network_create_success, name),
            LENGTH_SHORT).show();
        finish();
    }

    /** Push the modified config to the daemon if it already knows the network. */
    private void syncToDaemon(@NonNull NetworkConfig config) {
        var conn = DaemonConnection.getInstance();
        conn.buildRequest("network_exists")
            .put("network_id", config.getId())
            .onResponse(resp -> {
                if (!resp.optBoolean("exists", false)) return;
                conn.buildRequest("network_modify")
                    .put("config", config)
                    .onUnsuccessful(r -> {
                    })
                    .onError(e -> {
                    })
                    .invoke();
            })
            .onUnsuccessful(r -> {
            })
            .onError(e -> {
            })
            .invoke();
    }

    @Nullable
    private String checkOverlaps(@NonNull NetworkConfig config) {
        var myV4 = new ArrayList<IPv4Network>();
        var myV6 = new ArrayList<IPv6Network>();
        for (var vlan : config.getVlans()) {
            var net4 = vlan.getIpv4Network();
            if (net4 != null) myV4.add(net4);
            for (var cidr : vlan.getIpv4Secondary()) {
                try {
                    myV4.add(IPv4Network.parse(cidr));
                } catch (Exception ignored) {
                }
            }
            var net6 = vlan.getIpv6Network();
            if (net6 != null) myV6.add(net6);
            for (var cidr : vlan.getIpv6Secondary()) {
                try {
                    myV6.add(IPv6Network.parse(cidr));
                } catch (Exception ignored) {
                }
            }
        }
        // overlaps within this network
        for (int i = 0; i < myV4.size(); i++)
            for (int j = i + 1; j < myV4.size(); j++)
                if (myV4.get(i).overlaps(myV4.get(j)))
                    return getString(R.string.network_edit_error_self_overlap,
                        myV4.get(i).toString(), myV4.get(j).toString());
        for (int i = 0; i < myV6.size(); i++)
            for (int j = i + 1; j < myV6.size(); j++)
                if (myV6.get(i).overlaps(myV6.get(j)))
                    return getString(R.string.network_edit_error_self_overlap,
                        myV6.get(i).toString(), myV6.get(j).toString());
        // overlaps against other networks
        var result = new String[1];
        store.forEach((id, other) -> {
            if (result[0] != null || id.equals(editNetworkId)) return;
            for (var vlan : other.getVlans()) {
                var otherNet = vlan.getIpv4Network();
                if (otherNet != null) {
                    for (var mine : myV4) {
                        if (!mine.overlaps(otherNet)) continue;
                        result[0] = getString(R.string.network_edit_error_ipv4_overlap,
                            mine.toString(), other.getName(), otherNet);
                        return;
                    }
                }
                var otherNet6 = vlan.getIpv6Network();
                if (otherNet6 != null) {
                    for (var mine : myV6) {
                        if (!mine.overlaps(otherNet6)) continue;
                        result[0] = getString(R.string.network_edit_error_ipv6_overlap,
                            mine.toString(), other.getName(), otherNet6);
                        return;
                    }
                }
            }
        });
        return result[0];
    }
}
