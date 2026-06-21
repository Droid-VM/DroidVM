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
import cn.classfun.droidvm.daemon.network.backend.UplinkResolver;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.Ipv6Source;
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
    /** bridge + "v"/"." + 2-char VLAN code must fit IFNAMSIZ (15 usable). */
    private static final int MAX_BRIDGE_NAME_LEN = 12;
    /** Interface-name charset: ASCII letters, digits, hyphen, underscore. */
    private static final InputFilter BRIDGE_NAME_CHARSET = (src, start, end, dst, ds, de) ->
        src.subSequence(start, end).toString().matches("[A-Za-z0-9_-]*") ? null : "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
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
    private String[] bridgeTypeLabels;
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
        inputBridge.setFilters(new InputFilter[]{
            new InputFilter.LengthFilter(MAX_BRIDGE_NAME_LEN),
            BRIDGE_NAME_CHARSET,
        });
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
    private void rebuildUplinks(@Nullable org.json.JSONObject data) {
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
        @Nullable org.json.JSONObject data
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
        var vlan = VlanConfig.createDefault(vlanId);
        var pair = generatePairedCidrs();
        if (pair != null) {
            vlan.ipv4().set("cidr", pair[0]);
            vlan.ipv6().set("cidr", pair[1]);
        }
        var ipv6 = vlan.ipv6();
        if (bridgeType == BridgeType.GVISOR) {
            // gVisor has IPv6 SNAT, so the ULA prefix is routable: default on
            ipv6.set("snat", true);
        } else {
            // a Linux bridge has no IPv6 NAT and Android rarely holds a
            // routed prefix, so serving the ULA via DHCPv6/SLAAC hands VMs
            // addresses with no connectivity: default to a static ULA CIDR
            // with serving off, and pre-fill the Wi-Fi PD uplink for when the
            // user switches the source to DHCP-PD
            ipv6.set("snat", false);
            ipv6.set("source", Ipv6Source.STATIC.key());
            var pd = DataItem.newObject();
            pd.set("uplink", UplinkResolver.ID_WIFI);
            ipv6.set("pd", pd);
            ipv6.get("dhcp").set("enabled", false);
            ipv6.get("slaac").set("enabled", false);
        }
        return vlan;
    }

    /**
     * Picks N in 50-250 so that 192.168.N.1/24 and fd00:N::1/64 are both
     * free of overlaps with every other network and this network's other
     * VLANs. Returns null when no N fits.
     */
    @Nullable
    private String[] generatePairedCidrs() {
        var used4 = new ArrayList<IPv4Network>();
        var used6 = new ArrayList<IPv6Network>();
        collectUsedNetworks(used4, used6);
        for (int attempt = 0; attempt < 400; attempt++) {
            int n = 50 + random.nextInt(201); // 50-250
            IPv4Network cand4;
            IPv6Network cand6;
            try {
                cand4 = IPv4Network.parse(fmt("192.168.%d.1/24", n));
                cand6 = IPv6Network.parse(fmt("fd00:%d::1/64", n));
            } catch (Exception e) {
                continue;
            }
            boolean conflicts = false;
            for (var ex : used4)
                if (cand4.overlaps(ex)) {
                    conflicts = true;
                    break;
                }
            if (!conflicts) for (var ex : used6)
                if (cand6.overlaps(ex)) {
                    conflicts = true;
                    break;
                }
            if (!conflicts) return new String[]{cand4.toString(), cand6.toString()};
        }
        return null;
    }

    /** Subnets in use by other networks and by this network's VLAN cards. */
    private void collectUsedNetworks(
        @NonNull List<IPv4Network> out4, @NonNull List<IPv6Network> out6
    ) {
        storeAllBinders();
        var sources = new ArrayList<VlanConfig>(vlans);
        store.forEach((id, cfg) -> {
            if (id.equals(editNetworkId)) return;
            sources.addAll(cfg.getVlans());
        });
        for (var vlan : sources) {
            var net4 = vlan.getIpv4Network();
            if (net4 != null) out4.add(net4);
            for (var cidr : vlan.getIpv4Secondary()) {
                try {
                    out4.add(IPv4Network.parse(cidr));
                } catch (Exception ignored) {
                }
            }
            var net6 = vlan.getIpv6Network();
            if (net6 != null) out6.add(net6);
            for (var cidr : vlan.getIpv6Secondary()) {
                try {
                    out6.add(IPv6Network.parse(cidr));
                } catch (Exception ignored) {
                }
            }
        }
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
        var used = new java.util.HashSet<Integer>();
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
            || bridgeName.length() > MAX_BRIDGE_NAME_LEN) {
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
