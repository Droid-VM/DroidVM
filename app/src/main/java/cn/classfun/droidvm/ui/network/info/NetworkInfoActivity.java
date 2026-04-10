package cn.classfun.droidvm.ui.network.info;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.widget.LinearLayout.VERTICAL;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.store.enums.Enums.applyText;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.network.IPv4Address;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkState;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.ui.SwipeableTabActivity;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.ui.network.NetworkActions;
import cn.classfun.droidvm.ui.network.edit.NetworkEditActivity;

public final class NetworkInfoActivity extends SwipeableTabActivity {
    private static final String TAG = "NetworkInfoActivity";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = () -> {
        syncState();
        scheduleRefresh();
    };
    private boolean resumed = false;
    private UUID networkId;
    private NetworkConfig config;
    private NetworkStore store;
    private NetworkState currentState = NetworkState.STOPPED;
    private CollapsingToolbarLayout collapsingToolbar;
    private TabLayout tabLayout;
    private FrameLayout tabContainer;
    private NestedScrollView[] tabScrollViews;
    private LinearLayout[] tabContainers;
    private int currentTabIndex = 0;
    private boolean hasDhcp = false;
    private TextView tvState, tvStateDetail;
    private MaterialButton btnStartStop, btnEdit, btnDelete;
    private MaterialToolbar toolbar;
    private JSONArray liveAddresses = new JSONArray();
    private JSONArray liveInterfaces = new JSONArray();
    private JSONArray neighbors = new JSONArray();
    private JSONArray dhcpLeases = new JSONArray();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_info);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        tabLayout = findViewById(R.id.tab_layout);
        tabContainer = findViewById(R.id.tab_container);
        toolbar = findViewById(R.id.toolbar);
        initialize();
    }

    private void initialize() {
        var id = getIntent().getStringExtra("target_id");
        if (id == null) {
            finish();
            return;
        }
        networkId = UUID.fromString(id);
        toolbar.setNavigationOnClickListener(v -> finish());
        initSwipeHelper();
        store = new NetworkStore();
    }

    @Override
    public int getTabCount() {
        return tabScrollViews != null ? tabScrollViews.length : 0;
    }

    @Override
    public int getCurrentTabIndex() {
        return currentTabIndex;
    }

    @NonNull
    @Override
    public View getTabView(int index) {
        return tabScrollViews[index];
    }

    @Override
    public void onTabSwitchedByDrag(int newIndex) {
        currentTabIndex = newIndex;
        tabLayout.selectTab(tabLayout.getTabAt(newIndex));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        reloadConfig();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        mainHandler.removeCallbacks(refreshRunnable);
    }

    private void scheduleRefresh() {
        if (resumed) mainHandler.postDelayed(refreshRunnable, 3000);
    }

    private void reloadConfig() {
        runOnPool(() -> {
            store.load(this);
            config = store.findById(networkId);
            runOnUiThread(() -> {
                if (config == null) {
                    finish();
                    return;
                }
                populateInfo();
                syncState();
                scheduleRefresh();
            });
        });
    }

    private void populateInfo() {
        collapsingToolbar.setTitle(config.getName());
        setupTabs();
    }

    private void setupTabs() {
        hasDhcp = config.item.optBoolean("dhcp_enabled", false);
        int count = hasDhcp ? 5 : 4;
        tabContainer.removeAllViews();
        tabLayout.clearOnTabSelectedListeners();
        tabLayout.removeAllTabs();
        tabScrollViews = new NestedScrollView[count];
        tabContainers = new LinearLayout[count];
        for (int i = 0; i < count; i++) {
            var sv = new NestedScrollView(this);
            sv.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            var ll = new LinearLayout(this);
            ll.setOrientation(VERTICAL);
            ll.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            sv.addView(ll);
            tabContainer.addView(sv);
            tabScrollViews[i] = sv;
            tabContainers[i] = ll;
        }
        for (var title : getTabTitles())
            tabLayout.addTab(tabLayout.newTab().setText(title));
        currentTabIndex = 0;
        swipeHelper.setTabImmediate(0);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int newPos = tab.getPosition();
                if (swipeHelper.isSettling()) return;
                int direction = Integer.compare(newPos, currentTabIndex);
                currentTabIndex = newPos;
                swipeHelper.animateToTab(newPos, direction);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        refreshTabContents();
    }

    @NonNull
    private List<String> getTabTitles() {
        var titles = new ArrayList<String>();
        titles.add(getString(R.string.network_info_tab_basic));
        titles.add(getString(R.string.network_info_tab_addresses));
        titles.add(getString(R.string.network_info_tab_ports));
        if (config != null && config.item.optBoolean("dhcp_enabled", false))
            titles.add(getString(R.string.network_info_tab_dhcp));
        titles.add(getString(R.string.network_info_tab_neighbors));
        return titles;
    }

    private int resolveTabType(int position) {
        if (position <= TAB_PORTS) return position;
        if (hasDhcp) return position;
        return position + 1;
    }

    private void refreshTabContents() {
        if (tabContainers == null) return;
        for (int i = 0; i < tabContainers.length; i++) {
            tabContainers[i].removeAllViews();
            switch (resolveTabType(i)) {
                case TAB_BASIC:
                    bindBasic(tabContainers[i]);
                    break;
                case TAB_ADDRESSES:
                    bindAddresses(tabContainers[i]);
                    break;
                case TAB_PORTS:
                    bindPorts(tabContainers[i]);
                    break;
                case TAB_DHCP:
                    bindDhcp(tabContainers[i]);
                    break;
                case TAB_NEIGHBORS_WITH_DHCP:
                    bindNeighbors(tabContainers[i]);
                    break;
            }
        }
    }

    private void syncState() {
        DaemonConnection.OnResponse res = resp -> {
            var data = resp.optJSONObject("data");
            mainHandler.post(() -> {
                if (isFinishing()) return;
                if (data != null) {
                    currentState = optEnum(data, "state", NetworkState.STOPPED);
                    liveAddresses = data.optJSONArray("live_addresses");
                    if (liveAddresses == null) liveAddresses = new JSONArray();
                    liveInterfaces = data.optJSONArray("live_interfaces");
                    if (liveInterfaces == null) liveInterfaces = new JSONArray();
                    neighbors = data.optJSONArray("neighbors");
                    if (neighbors == null) neighbors = new JSONArray();
                    dhcpLeases = data.optJSONArray("dhcp_leases");
                    if (dhcpLeases == null) dhcpLeases = new JSONArray();
                }
                updateStateUI();
                refreshTabContents();
            });
        };
        DaemonConnection.getInstance()
            .buildRequest("network_info")
            .put("network_id", networkId.toString())
            .onResponse(res)
            .onUnsuccessful(resp -> syncStateFromList())
            .onError(e -> syncStateFromList())
            .invoke();
    }

    private void syncStateFromList() {
        DaemonConnection.OnResponse res = resp -> {
            var arr = resp.optJSONArray("data");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                var net = arr.optJSONObject(i);
                if (net == null) continue;
                if (networkId.equals(UUID.fromString(net.optString("id", "")))) {
                    var s = optEnum(net, "state", NetworkState.STOPPED);
                    mainHandler.post(() -> {
                        currentState = s;
                        updateStateUI();
                    });
                    return;
                }
            }
            mainHandler.post(() -> {
                currentState = NetworkState.STOPPED;
                updateStateUI();
            });
        };
        DaemonConnection.getInstance()
            .buildRequest("network_list")
            .onResponse(res)
            .onUnsuccessful(r -> {})
            .onError(e -> Log.w(TAG, "Failed to sync state", e))
            .invoke();
    }

    private void updateStateUI() {
        if (tvState == null) return;
        applyText(tvState, currentState);
        var isStopped = currentState == NetworkState.STOPPED;
        var isRunning = currentState == NetworkState.RUNNING;
        if (isRunning) {
            btnStartStop.setText(R.string.network_info_stop);
            btnStartStop.setIconResource(R.drawable.ic_vm_stop);
            tvStateDetail.setText(R.string.network_info_state_active);
        } else if (isStopped) {
            btnStartStop.setText(R.string.network_info_start);
            btnStartStop.setIconResource(R.drawable.ic_vm_start);
            tvStateDetail.setText(R.string.network_info_state_idle);
        } else {
            btnStartStop.setText(R.string.network_info_start);
            btnStartStop.setIconResource(R.drawable.ic_vm_start);
            tvStateDetail.setText("");
        }
        btnStartStop.setEnabled(isStopped || isRunning);
        btnEdit.setEnabled(isStopped);
        btnDelete.setEnabled(isStopped);
    }

    private void onStartStopClicked() {
        if (currentState == NetworkState.RUNNING) {
            doStop();
        } else {
            doStart();
        }
    }

    private void doStart() {
        if (config == null) return;
        var context = UIContext.fromActivity(this);
        NetworkActions.createAndStart(config, mainHandler, context,
            () -> mainHandler.post(this::syncState));
    }

    private void doStop() {
        DaemonConnection.getInstance().buildRequest("network_stop")
            .put("network_id", networkId.toString())
            .onResponse(resp -> mainHandler.post(this::syncState))
            .onUnsuccessful(resp -> showError(resp.optString("message", "Failed to stop network")))
            .onError(e -> showError("Failed to stop network"))
            .invoke();
    }

    private void openEdit() {
        var intent = new Intent(this, NetworkEditActivity.class);
        intent.putExtra(NetworkEditActivity.EXTRA_NETWORK_ID, networkId.toString());
        startActivity(intent);
    }

    private void doDelete() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(config.getName())
            .setMessage(R.string.network_delete_confirm)
            .setPositiveButton(R.string.network_delete, (d, w) -> performDelete())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void performDelete() {
        NetworkActions.deleteNetwork(this, mainHandler, networkId, store, success -> {
            if (success) {
                Toast.makeText(this, R.string.network_delete, LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void showError(String msg) {
        mainHandler.post(() -> {
            if (!isFinishing())
                Toast.makeText(this, msg, LENGTH_LONG).show();
        });
    }

    private static final int TAB_BASIC = 0;
    private static final int TAB_ADDRESSES = 1;
    private static final int TAB_PORTS = 2;
    private static final int TAB_DHCP = 3;
    private static final int TAB_NEIGHBORS_WITH_DHCP = 4;

    private String strStatus(boolean stat) {
        return stat ?
            getString(R.string.network_info_enabled) :
            getString(R.string.network_info_disabled);
    }

    private void bindBasic(@NonNull LinearLayout container) {
        if (config == null) return;
        var header = LayoutInflater.from(this)
            .inflate(R.layout.partial_network_info_header, container, false);
        tvState = header.findViewById(R.id.tv_state);
        tvStateDetail = header.findViewById(R.id.tv_state_detail);
        btnStartStop = header.findViewById(R.id.btn_start_stop);
        btnEdit = header.findViewById(R.id.btn_edit);
        btnDelete = header.findViewById(R.id.btn_delete);
        btnStartStop.setOnClickListener(v -> onStartStopClicked());
        btnEdit.setOnClickListener(v -> openEdit());
        btnDelete.setOnClickListener(v -> doDelete());
        container.addView(header);
        updateStateUI();
        addInfoRow(container, R.drawable.ic_switch, getString(R.string.network_info_bridge),
            config.item.optString("bridge_name", ""));
        var mac = config.item.optString("mac_address", "");
        addInfoRow(container, R.drawable.ic_ethernet,
            getString(R.string.network_info_mac),
            mac.isEmpty() ? "-" : mac);
        addInfoRow(container, R.drawable.ic_refresh_auto,
            getString(R.string.network_info_auto_up),
            strStatus(config.item.optBoolean("auto_up", false)));
        addInfoRow(container, R.drawable.ic_file_tree,
            getString(R.string.network_info_stp),
            strStatus(config.item.optBoolean("stp", false)));
        addInfoRow(container, R.drawable.ic_web_plus,
            getString(R.string.network_info_nat),
            strStatus(config.item.optBoolean("nat", false)));
        addInfoRow(container, R.drawable.ic_router,
            getString(R.string.network_info_dhcp),
            strStatus(config.item.optBoolean("dhcp_enabled", false)));
        if (config.item.optBoolean("dhcp_enabled", false)) try {
            var startStr = config.item.optString("dhcp_range_start", "");
            var endStr = config.item.optString("dhcp_range_end", "");
            if (!startStr.isEmpty() && !endStr.isEmpty()) {
                var start = requireNonNull(IPv4Address.parse(startStr));
                var end = requireNonNull(IPv4Address.parse(endStr));
                long count = end.value() - start.value() + 1;
                if (count < 0) count = 0;
                var range = getResources().getQuantityString(
                    R.plurals.network_info_dhcp_pool, (int) count,
                    start.toString(), end.toString(), count
                );
                addInfoRow(container, R.drawable.ic_ip_network,
                    getString(R.string.network_info_dhcp_range), range);
            }
        } catch (Exception ignored) {
        }
        var df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        addInfoRow(container, R.drawable.ic_clock, getString(R.string.network_info_created),
            df.format(new Date(config.item.optLong("created_at", 0))));
    }

    private void bindAddresses(@NonNull LinearLayout container) {
        boolean hasAny = false;
        if (config != null) {
            config.item.get("ipv4_addresses").forEachArray(a -> {
                try {
                    addEntry(container, R.drawable.ic_ip_network,
                        a.asString(), "IPv4 (configured)");
                } catch (Exception ignored) {
                }
            });
            config.item.get("ipv6_addresses").forEachArray(a -> {
                try {
                    addEntry(container, R.drawable.ic_ip_network,
                        a.asString(), "IPv6 (configured)");
                } catch (Exception ignored) {
                }
            });
            hasAny = !config.item.get("ipv4_addresses").isEmpty() ||
                !config.item.get("ipv6_addresses").isEmpty();
        }
        for (int i = 0; i < liveAddresses.length(); i++) {
            var addr = liveAddresses.optString(i, "");
            if (addr.isEmpty()) continue;
            addEntry(container, R.drawable.ic_ip_network, addr,
                addr.contains(":") ? "IPv6 (live)" : "IPv4 (live)");
            hasAny = true;
        }
        if (!hasAny) addEmptyView(container, R.string.network_info_no_addresses);
    }

    private void bindPorts(@NonNull LinearLayout container) {
        if (liveInterfaces.length() == 0) {
            addEmptyView(container, R.string.network_info_no_ports);
            return;
        }
        for (int i = 0; i < liveInterfaces.length(); i++) {
            var iface = liveInterfaces.optJSONObject(i);
            if (iface == null) {
                var name = liveInterfaces.optString(i, "");
                if (!name.isEmpty())
                    addEntry(container, R.drawable.ic_ethernet, name, "");
                continue;
            }
            var name = iface.optString("name", "");
            var mac = iface.optString("address", "");
            var vmName = iface.optString("vm_name", "");
            if (!vmName.isEmpty()) {
                addEntry(container, R.drawable.ic_server, name,
                    fmt("%s - %s", vmName, mac));
            } else {
                addEntry(container, R.drawable.ic_ethernet, name, mac);
            }
        }
    }

    private void bindDhcp(@NonNull LinearLayout container) {
        if (dhcpLeases.length() == 0) {
            addEmptyView(container, R.string.network_info_no_leases);
            return;
        }
        var sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < dhcpLeases.length(); i++) {
            var lease = dhcpLeases.optJSONObject(i);
            if (lease == null) continue;
            var ip = lease.optString("ip", "");
            var mac = lease.optString("mac", "");
            var hostname = lease.optString("hostname", "*");
            var expires = lease.optString("expires", "0");
            long ts;
            try {
                ts = Long.parseLong(expires);
            } catch (NumberFormatException e) {
                ts = 0;
            }
            var expStr = ts > 0 ? getString(R.string.network_info_dhcp_expires,
                sdf.format(new Date(ts * 1000))) : "";
            var line2 = fmt("%s - %s", mac, hostname);
            if (!expStr.isEmpty()) line2 = fmt("%s\n%s", line2, expStr);
            addEntry(container, R.drawable.ic_ip_network, ip, line2);
        }
    }

    private void bindNeighbors(@NonNull LinearLayout container) {
        if (neighbors.length() == 0) {
            addEmptyView(container, R.string.network_info_no_neighbors);
            return;
        }
        for (int i = 0; i < neighbors.length(); i++) {
            var neigh = neighbors.optJSONObject(i);
            if (neigh == null) continue;
            var dst = neigh.optString("dst", "");
            var lladdr = neigh.optString("lladdr", "");
            var stateArr = neigh.optJSONArray("state");
            var state = "";
            if (stateArr != null) {
                var sb = new StringBuilder();
                for (int j = 0; j < stateArr.length(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(stateArr.optString(j, ""));
                }
                state = sb.toString();
            }
            var line2 = lladdr.isEmpty() ? state : fmt("%s - %s", lladdr, state);
            addEntry(container, R.drawable.ic_ethernet, dst, line2);
        }
    }

    private void addInfoRow(
        @NonNull LinearLayout container,
        int iconRes, String label, String value
    ) {
        var view = LayoutInflater.from(this)
            .inflate(R.layout.item_network_entry, container, false);
        ImageView iv_icon = view.findViewById(R.id.iv_icon);
        TextView tv_line1 = view.findViewById(R.id.tv_line1);
        TextView tv_line2 = view.findViewById(R.id.tv_line2);
        iv_icon.setImageResource(iconRes);
        tv_line1.setText(label);
        tv_line2.setText(value);
        tv_line2.setVisibility(value.isEmpty() ? GONE : VISIBLE);
        container.addView(view);
    }

    private void addEntry(
        @NonNull LinearLayout container,
        int iconRes, String line1, String line2
    ) {
        var view = LayoutInflater.from(this)
            .inflate(R.layout.item_network_entry, container, false);
        ImageView iv_icon = view.findViewById(R.id.iv_icon);
        TextView tv_line1 = view.findViewById(R.id.tv_line1);
        TextView tv_line2 = view.findViewById(R.id.tv_line2);
        iv_icon.setImageResource(iconRes);
        tv_line1.setText(line1);
        tv_line2.setText(line2);
        tv_line2.setVisibility(line2.isEmpty() ? GONE : VISIBLE);
        container.addView(view);
    }

    private void addEmptyView(@NonNull LinearLayout container, int msgRes) {
        var view = LayoutInflater.from(this)
            .inflate(R.layout.partial_empty_hint, container, false);
        TextView tv_empty = view.findViewById(R.id.tv_empty);
        tv_empty.setText(msgRes);
        container.addView(view);
    }
}
