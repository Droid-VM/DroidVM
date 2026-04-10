package cn.classfun.droidvm.ui.network.edit;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.utils.NetUtils.generateRandomMac;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.Bundle;
import android.text.Editable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.network.IPNetwork;
import cn.classfun.droidvm.lib.network.IPv4Address;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class NetworkEditActivity extends AppCompatActivity {
    public static final String EXTRA_NETWORK_ID = "network_id";
    private final List<IPv4Network> ipv4Addresses = new ArrayList<>();
    private final List<IPv6Network> ipv6Addresses = new ArrayList<>();
    private boolean editMode = false;
    private UUID editNetworkId = null;
    private CollapsingToolbarLayout collapsingToolbar;
    private TextInputRowWidget inputName, inputBridgeName, inputMac;
    private SwitchRowWidget swAutoUp, swStp, swNat, swDhcp;
    private TextInputRowWidget inputIPv4, inputIPv6;
    private LinearLayout layoutIPv4, layoutIPv6, groupDhcp;
    private TextView tvIPv4Empty, tvIPv6Empty;
    private TextInputRowWidget inputDhcpStart, inputDhcpEnd;
    private FloatingActionButton fab;
    private MaterialToolbar toolbar;
    private NetworkStore store;
    @SuppressWarnings("unused")
    private boolean macManuallyEdited = false;
    @SuppressWarnings("unused")
    private boolean bridgeManuallyEdited = false;
    @SuppressWarnings("unused")
    private boolean ipv4ManuallyEdited = false;
    @SuppressWarnings("unused")
    private boolean dhcpStartManuallyEdited = false;
    @SuppressWarnings("unused")
    private boolean dhcpEndManuallyEdited = false;
    @SuppressWarnings("unused")
    private boolean programmaticChange = false;

    private final Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_edit);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        toolbar = findViewById(R.id.toolbar);
        inputName = findViewById(R.id.input_name);
        inputBridgeName = findViewById(R.id.input_bridge_name);
        inputMac = findViewById(R.id.input_mac);
        swAutoUp = findViewById(R.id.sw_auto_up);
        swStp = findViewById(R.id.sw_stp);
        swNat = findViewById(R.id.sw_nat);
        swDhcp = findViewById(R.id.sw_dhcp);
        inputIPv4 = findViewById(R.id.input_ipv4);
        inputIPv6 = findViewById(R.id.input_ipv6);
        layoutIPv4 = findViewById(R.id.layout_ipv4);
        layoutIPv6 = findViewById(R.id.layout_ipv6);
        tvIPv4Empty = findViewById(R.id.tv_ipv4_empty);
        tvIPv6Empty = findViewById(R.id.tv_ipv6_empty);
        groupDhcp = findViewById(R.id.group_dhcp);
        inputDhcpStart = findViewById(R.id.input_dhcp_start);
        inputDhcpEnd = findViewById(R.id.input_dhcp_end);
        fab = findViewById(R.id.fab_save);
        initialize();
    }

    private void initialize() {
        toolbar.setNavigationOnClickListener(v -> finish());
        store = new NetworkStore();
        store.load(this);
        inputIPv4.setEndIconOnClickListener(v -> onAddIPv4());
        inputIPv6.setEndIconOnClickListener(v -> onAddIPv6());
        swDhcp.setOnCheckedChangeListener((btn, checked) ->
            groupDhcp.setVisibility(checked ? VISIBLE : GONE));
        fab.setOnClickListener(v -> onSaveClicked());
        installManualEditWatchers();
        var intent = getIntent();
        if (intent != null && intent.hasExtra(EXTRA_NETWORK_ID)) {
            editMode = true;
            editNetworkId = UUID.fromString(intent.getStringExtra(EXTRA_NETWORK_ID));
            collapsingToolbar.setTitle(getString(R.string.network_edit_title));
            loadExistingConfig();
        } else {
            collapsingToolbar.setTitle(getString(R.string.network_create_title));
            generateDefaults();
        }
        buildAddressList(layoutIPv4, tvIPv4Empty, ipv4Addresses, true);
        buildAddressList(layoutIPv6, tvIPv6Empty, ipv6Addresses, false);
    }

    private void installManualEditWatchers() {
        inputMac.addTextChangedListener(new ManualEditWatcher() {
            @Override
            protected void onManualEdit() {
                macManuallyEdited = true;
                propagateMacToBridge();
            }
        });
        inputBridgeName.addTextChangedListener(new ManualEditWatcher() {
            @Override
            protected void onManualEdit() {
                bridgeManuallyEdited = true;
            }
        });
        inputDhcpStart.addTextChangedListener(new ManualEditWatcher() {
            @Override
            protected void onManualEdit() {
                dhcpStartManuallyEdited = true;
            }
        });
        inputDhcpEnd.addTextChangedListener(new ManualEditWatcher() {
            @Override
            protected void onManualEdit() {
                dhcpEndManuallyEdited = true;
            }
        });
    }

    private abstract class ManualEditWatcher extends SimpleTextWatcher {
        @Override
        public void afterTextChanged(Editable s) {
            if (!programmaticChange) onManualEdit();
        }

        protected abstract void onManualEdit();
    }

    private void generateDefaults() {
        var mac = generateRandomMac();
        setTextProgrammatic(inputMac, mac);
        setTextProgrammatic(inputBridgeName, bridgeNameFromMac(mac));
        var ipv4Cidr = generateRandomIPv4();
        ipv4Addresses.add(ipv4Cidr);
        swDhcp.setChecked(true);
        updateDhcpFromIPv4();
    }

    @NonNull
    private String bridgeNameFromMac(@NonNull String mac) {
        return fmt("br%s", mac.replace(":", "").toLowerCase());
    }

    @NonNull
    private IPv4Network generateRandomIPv4() {
        var existing = new ArrayList<IPv4Network>();
        store.forEach((id, cfg) -> {
            if (id.equals(editNetworkId)) return;
            cfg.item.get("ipv4_addresses").forEachArray(a -> {
                try {
                    existing.add(IPv4Network.parse(a.asString()));
                } catch (Exception ignored) {
                }
            });
        });
        for (int attempt = 0; attempt < 200; attempt++) {
            int b = 180 + random.nextInt(10);  // 180-189
            int c = random.nextInt(256);       // 0-255
            var addr = new IPv4Address(10, b, c, 1);
            var candidate = new IPv4Network(addr, 24);
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

    private void updateDhcpFromIPv4() {
        if (ipv4Addresses.isEmpty()) return;
        var cidr = ipv4Addresses.get(0);
        if (cidr == null) return;
        if (!dhcpStartManuallyEdited) {
            var start = cidr.dhcpPoolStart();
            if (start != null) setTextProgrammatic(inputDhcpStart, start.toString());
        }
        if (!dhcpEndManuallyEdited) {
            var end = cidr.dhcpPoolEnd();
            if (end != null) setTextProgrammatic(inputDhcpEnd, end.toString());
        }
    }

    private void propagateMacToBridge() {
        if (bridgeManuallyEdited) return;
        var mac = inputMac.getText().trim();
        if (mac.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))
            setTextProgrammatic(inputBridgeName, bridgeNameFromMac(mac));
    }

    private void setTextProgrammatic(@NonNull TextInputRowWidget w, @NonNull String text) {
        programmaticChange = true;
        w.setText(text);
        programmaticChange = false;
    }

    private void loadExistingConfig() {
        var config = store.findById(editNetworkId);
        if (config == null) {
            Toast.makeText(this, R.string.network_edit_error_not_found, LENGTH_LONG).show();
            finish();
            return;
        }
        programmaticChange = true;
        inputName.setText(config.getName());
        inputBridgeName.setText(config.item.optString("bridge_name", ""));
        inputMac.setText(config.item.optString("mac_address", ""));
        programmaticChange = false;
        swAutoUp.setChecked(config.item.optBoolean("auto_up", false));
        swStp.setChecked(config.item.optBoolean("stp", false));
        swNat.setChecked(config.item.optBoolean("nat", false));
        swDhcp.setChecked(config.item.optBoolean("dhcp_enabled", false));
        programmaticChange = true;
        var start = config.item.optString("dhcp_range_start", "");
        var end = config.item.optString("dhcp_range_end", "");
        inputDhcpStart.setText(start);
        inputDhcpEnd.setText(end);
        programmaticChange = false;
        ipv4Addresses.clear();
        parseIPv4Addresses(config, ipv4Addresses);
        ipv6Addresses.clear();
        parseIPv6Addresses(config, ipv6Addresses);
        macManuallyEdited = true;
        bridgeManuallyEdited = true;
        ipv4ManuallyEdited = true;
        dhcpStartManuallyEdited = true;
        dhcpEndManuallyEdited = true;
    }

    private void onAddIPv4() {
        var addr = inputIPv4.getText().trim();
        if (addr.isEmpty()) return;
        if (!IPv4Network.isValid(addr)) {
            inputIPv4.setError(getString(R.string.network_edit_error_address_invalid));
            return;
        }
        IPv4Network ip;
        try {
            ip = IPv4Network.parse(addr);
        } catch (Exception ignored) {
            inputIPv4.setError(getString(R.string.network_edit_error_address_invalid));
            return;
        }
        inputIPv4.setError(null);
        ipv4Addresses.add(ip);
        ipv4ManuallyEdited = true;
        inputIPv4.setText("");
        buildAddressList(layoutIPv4, tvIPv4Empty, ipv4Addresses, true);
        updateDhcpFromIPv4();
    }

    private void onAddIPv6() {
        var addr = inputIPv6.getText().trim();
        if (addr.isEmpty()) return;
        if (!IPv6Network.isValid(addr)) {
            inputIPv6.setError(getString(R.string.network_edit_error_address_invalid));
            return;
        }
        IPv6Network ip;
        try {
            ip = IPv6Network.parse(addr);
        } catch (Exception ignored) {
            inputIPv6.setError(getString(R.string.network_edit_error_address_invalid));
            return;
        }
        inputIPv6.setError(null);
        ipv6Addresses.add(ip);
        inputIPv6.setText("");
        buildAddressList(layoutIPv6, tvIPv6Empty, ipv6Addresses, false);
    }

    private void buildAddressList(
        @NonNull LinearLayout container,
        @NonNull TextView emptyView,
        @NonNull List<? extends IPNetwork<?, ?, ?>> list,
        boolean isIPv4
    ) {
        container.removeAllViews();
        if (list.isEmpty()) {
            emptyView.setVisibility(VISIBLE);
        } else {
            emptyView.setVisibility(GONE);
            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                var row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = (int) (4 * getResources().getDisplayMetrics().density);
                row.setPadding(0, pad, 0, pad);
                var tv = new TextView(this);
                tv.setText(list.get(i).toString());
                tv.setTextSize(15);
                var lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tv.setLayoutParams(lp);
                row.addView(tv);
                var btn = new ImageButton(this);
                btn.setImageResource(R.drawable.ic_delete);
                btn.setBackground(null);
                btn.setContentDescription(getString(R.string.network_edit_remove_address));
                btn.setOnClickListener(v -> {
                    list.remove(idx);
                    buildAddressList(container, emptyView, list, isIPv4);
                    if (isIPv4) updateDhcpFromIPv4();
                });
                row.addView(btn);
                container.addView(row);
            }
        }
    }

    private void onSaveClicked() {
        inputName.setError(null);
        inputBridgeName.setError(null);
        inputMac.setError(null);
        inputIPv4.setError(null);
        inputIPv6.setError(null);
        inputDhcpStart.setError(null);
        inputDhcpEnd.setError(null);
        if (!inputIPv4.getText().trim().isEmpty()) {
            inputIPv4.setError(getString(R.string.network_edit_error_ipv4_not_added));
            return;
        }
        if (!inputIPv6.getText().trim().isEmpty()) {
            inputIPv6.setError(getString(R.string.network_edit_error_ipv6_not_added));
            return;
        }
        var name = inputName.getText().trim();
        var bridgeName = inputBridgeName.getText().trim();
        var mac = inputMac.getText().trim();
        if (name.isEmpty()) {
            inputName.setError(getString(R.string.network_edit_error_name_empty));
            return;
        }
        if (bridgeName.isEmpty()) {
            inputBridgeName.setError(getString(R.string.network_edit_error_bridge_empty));
            return;
        }
        if (!bridgeName.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            inputBridgeName.setError(getString(R.string.network_edit_error_bridge_invalid));
            return;
        }
        if (!mac.isEmpty() && !mac.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) {
            inputMac.setError(getString(R.string.network_edit_error_mac_invalid));
            return;
        }
        if (!store.isNameUnique(name, editNetworkId)) {
            inputName.setError(getString(R.string.network_edit_error_name_duplicate));
            return;
        }
        if (!isBridgeUnique(bridgeName)) {
            inputBridgeName.setError(getString(R.string.network_edit_error_bridge_duplicate));
            return;
        }
        if (!mac.isEmpty() && !isMacUnique(mac)) {
            inputMac.setError(getString(R.string.network_edit_error_mac_duplicate));
            return;
        }
        var ipv4Conflict = checkIPv4Overlap();
        if (ipv4Conflict != null) {
            Toast.makeText(this, ipv4Conflict, LENGTH_LONG).show();
            return;
        }
        var ipv6Conflict = checkIPv6Overlap();
        if (ipv6Conflict != null) {
            Toast.makeText(this, ipv6Conflict, LENGTH_LONG).show();
            return;
        }
        if (swDhcp.isChecked()) {
            var dhcpStart = inputDhcpStart.getText().trim();
            var dhcpEnd = inputDhcpEnd.getText().trim();
            if (!dhcpStart.isEmpty() && !IPv4Address.isValid(dhcpStart)) {
                inputDhcpStart.setError(getString(R.string.network_edit_error_dhcp_start_invalid));
                return;
            }
            if (!dhcpEnd.isEmpty() && !IPv4Address.isValid(dhcpEnd)) {
                inputDhcpEnd.setError(getString(R.string.network_edit_error_dhcp_end_invalid));
                return;
            }
            if (!dhcpStart.isEmpty() && !dhcpEnd.isEmpty()) {
                long start = requireNonNull(IPv4Address.parse(dhcpStart)).value();
                long end = requireNonNull(IPv4Address.parse(dhcpEnd)).value();
                if (start >= end) {
                    inputDhcpStart.setError(getString(R.string.network_edit_error_dhcp_invalid));
                    return;
                }
            }
        }
        var config = new NetworkConfig();
        if (editMode && editNetworkId != null)
            config.setId(editNetworkId);
        config.setName(name);
        config.item.set("bridge_name", bridgeName);
        config.item.set("mac_address", mac);
        config.item.set("auto_up", swAutoUp.isChecked());
        config.item.set("stp", swStp.isChecked());
        config.item.set("nat", swNat.isChecked());
        config.item.set("dhcp_enabled", swDhcp.isChecked());
        setIPv4Addresses(config, ipv4Addresses);
        setIPv6Addresses(config, ipv6Addresses);
        config.item.set("dhcp_range_start", inputDhcpStart.getText().trim());
        config.item.set("dhcp_range_end", inputDhcpEnd.getText().trim());
        if (editMode) {
            store.update(config);
        } else {
            store.add(config);
        }
        store.save(this);
        Toast.makeText(this,
            editMode ? getString(R.string.network_edit_saved, name) :
                getString(R.string.network_create_success, name),
            LENGTH_SHORT).show();
        finish();
    }

    private boolean isBridgeUnique(@NonNull String bridge) {
        final boolean[] unique = {true};
        store.forEach((id, cfg) -> {
            if (id.equals(editNetworkId)) return;
            if (bridge.equals(cfg.item.optString("bridge_name", ""))) unique[0] = false;
        });
        return unique[0];
    }

    private boolean isMacUnique(@NonNull String mac) {
        final boolean[] unique = {true};
        var macLower = mac.toLowerCase(Locale.ROOT);
        store.forEach((id, cfg) -> {
            if (id.equals(editNetworkId)) return;
            if (macLower.equals(cfg.item.optString("mac_address", "").toLowerCase(Locale.ROOT)))
                unique[0] = false;
        });
        return unique[0];
    }

    @Nullable
    private String checkIPv4Overlap() {
        for (var myCidr : ipv4Addresses) {
            var result = new String[1];
            store.forEach((id, cfg) -> {
                if (result[0] != null) return;
                if (id.equals(editNetworkId)) return;
                var others = new ArrayList<IPv4Network>();
                parseIPv4Addresses(cfg, others);
                for (var other : others) {
                    if (!myCidr.overlaps(other)) continue;
                    result[0] = getString(
                        R.string.network_edit_error_ipv4_overlap,
                        myCidr.toString(), cfg.getName(), other
                    );
                    return;
                }
            });
            if (result[0] != null) return result[0];
        }
        return null;
    }

    @Nullable
    private String checkIPv6Overlap() {
        for (var myCidr : ipv6Addresses) {
            var result = new String[1];
            store.forEach((id, cfg) -> {
                if (result[0] != null) return;
                if (id.equals(editNetworkId)) return;
                var others = new ArrayList<IPv6Network>();
                parseIPv6Addresses(cfg, others);
                for (var other : others) {
                    if (!myCidr.overlaps(other)) continue;
                    result[0] = getString(
                        R.string.network_edit_error_ipv6_overlap,
                        myCidr.toString(), cfg.getName(), other
                    );
                    return;
                }
            });
            if (result[0] != null) return result[0];
        }
        return null;
    }

    private static void parseIPv4Addresses(@NonNull NetworkConfig cfg, @NonNull List<IPv4Network> out) {
        cfg.item.get("ipv4_addresses").forEachArray(a -> {
            try {
                out.add(IPv4Network.parse(a.asString()));
            } catch (Exception ignored) {
            }
        });
    }

    private static void parseIPv6Addresses(@NonNull NetworkConfig cfg, @NonNull List<IPv6Network> out) {
        cfg.item.get("ipv6_addresses").forEachArray(a -> {
            try {
                out.add(IPv6Network.parse(a.asString()));
            } catch (Exception ignored) {
            }
        });
    }

    private static void setIPv4Addresses(@NonNull NetworkConfig cfg, @NonNull List<IPv4Network> addresses) {
        var arr = DataItem.newArray();
        for (var addr : addresses)
            arr.append(DataItem.newString(addr.toString()));
        cfg.item.set("ipv4_addresses", arr);
    }

    private static void setIPv6Addresses(@NonNull NetworkConfig cfg, @NonNull List<IPv6Network> addresses) {
        var arr = DataItem.newArray();
        for (var addr : addresses)
            arr.append(DataItem.newString(addr.toString()));
        cfg.item.set("ipv6_addresses", arr);
    }
}
