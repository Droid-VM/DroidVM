package cn.classfun.droidvm.ui.vm.edit.network;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.NetUtils.generateRandomMac;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.store.vm.VMNicConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

public final class VMNetEditAdapter extends CardItemAdapter<VMNetEditViewHolder> {
    private NetworkStore networkStore;

    public VMNetEditAdapter(@NonNull Context context) {
        super(context);
    }

    @NonNull
    private NetworkStore networks() {
        if (networkStore == null) {
            networkStore = new NetworkStore();
            networkStore.load(context);
        }
        return networkStore;
    }

    @NonNull
    @Override
    protected VMNetEditViewHolder createViewHolderInstance(@NonNull View view) {
        return new VMNetEditViewHolder(view);
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.item_vm_net_edit;
    }

    @Override
    public void onBindViewHolder(@NonNull VMNetEditViewHolder holder, int position) {
        var net = items.get(position);
        var nic = new VMNicConfig(net);
        updateSelectButton(holder.btnSelect, nic.getNetworkId());
        holder.btnSelect.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            showNetworkPicker(pos, holder);
        });
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                removeItem(pos);
        });
        var mac = net.optString("mac_address", "");
        if (mac.isEmpty()) {
            mac = generateRandomMac();
            net.set("mac_address", mac);
        }
        holder.etMac.setText(mac);
        holder.etMac.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION)
                    items.get(pos).set("mac_address", getEditText(holder.etMac));
            }
        });
        holder.btnMacRandom.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            var randomMac = generateRandomMac();
            holder.etMac.setText(randomMac);
            items.get(pos).set("mac_address", randomMac);
        });
        bindSwitch(holder, holder.swMacSecurity, "mac_security",
            nic.isMacSecurity());
        bindSwitch(holder, holder.swIsolated, "isolated", nic.isIsolated());
        bindVlan(holder, nic);
        bindLeases(holder, nic);
    }

    private void bindSwitch(
        @NonNull VMNetEditViewHolder holder,
        @NonNull com.google.android.material.materialswitch.MaterialSwitch sw,
        @NonNull String key, boolean value
    ) {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                items.get(pos).set(key, checked);
        });
    }

    private void bindVlan(@NonNull VMNetEditViewHolder holder, @NonNull VMNicConfig nic) {
        var vlanId = nic.getVlanId();
        holder.swVlan.setOnCheckedChangeListener(null);
        holder.swVlan.setChecked(vlanId != null);
        holder.tilVlan.setVisibility(vlanId != null ? VISIBLE : GONE);
        holder.etVlan.setText(vlanId != null ? String.valueOf(vlanId) : "");
        holder.swVlan.setOnCheckedChangeListener((b, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            holder.tilVlan.setVisibility(checked ? VISIBLE : GONE);
            if (checked) {
                items.get(pos).set("vlan_id", parseLong(getEditText(holder.etVlan), 0));
            } else {
                items.get(pos).remove("vlan_id");
            }
            bindLeases(holder, new VMNicConfig(items.get(pos)));
        });
        holder.etVlan.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (holder.swVlan.isChecked())
                items.get(pos).set("vlan_id", parseLong(getEditText(holder.etVlan), 0));
            bindLeases(holder, new VMNicConfig(items.get(pos)));
        });
    }

    /** DHCP lease sections are only offered when the network's VLAN serves DHCP. */
    private void bindLeases(@NonNull VMNetEditViewHolder holder, @NonNull VMNicConfig nic) {
        var netId = nic.getNetworkId();
        var network = netId != null ? networks().findById(netId) : null;
        var vlan = network != null ? nic.resolveDhcpVlan(network) : null;
        boolean show4 = vlan != null && vlan.isDhcp4Enabled();
        boolean show6 = vlan != null && vlan.isDhcp6Enabled();
        holder.groupDhcp4.setVisibility(show4 ? VISIBLE : GONE);
        holder.groupDhcp6.setVisibility(show6 ? VISIBLE : GONE);
        if (show4) bindLease(holder, nic, vlan, network, false);
        if (show6) bindLease(holder, nic, vlan, network, true);
    }

    private void bindLease(
        @NonNull VMNetEditViewHolder holder, @NonNull VMNicConfig nic,
        @NonNull VlanConfig vlan, @NonNull NetworkConfig network, boolean v6
    ) {
        var sw = v6 ? holder.swDhcp6 : holder.swDhcp4;
        var detail = v6 ? holder.groupDhcp6Detail : holder.groupDhcp4Detail;
        var offset = v6 ? holder.etDhcp6Offset : holder.etDhcp4Offset;
        var fwdTil = v6 ? holder.tilFwd6 : holder.tilFwd4;
        var fwd = v6 ? holder.etFwd6 : holder.etFwd4;
        var leaseKey = v6 ? "dhcp6_lease" : "dhcp4_lease";
        boolean enabled = v6 ? nic.isDhcp6LeaseEnabled() : nic.isDhcp4LeaseEnabled();
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(enabled);
        detail.setVisibility(enabled ? VISIBLE : GONE);
        long currentOffset = v6 ? nic.getDhcp6Offset() : nic.getDhcp4Offset();
        offset.setText(String.valueOf(currentOffset));
        fwd.setText(formatForwards(
            v6 ? nic.getDhcp6Forwards() : nic.getDhcp4Forwards()));
        boolean snat = v6 ? vlan.isIpv6Snat() : vlan.isIpv4Snat();
        fwdTil.setEnabled(snat);
        fwdTil.setHelperText(snat ? context.getString(R.string.edit_vm_net_forwards_hint)
            : context.getString(R.string.edit_vm_net_forwards_need_snat));
        sw.setOnCheckedChangeListener((b, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            detail.setVisibility(checked ? VISIBLE : GONE);
            var lease = leaseItem(items.get(pos), leaseKey);
            lease.set("enabled", checked);
            if (checked && lease.optLong("offset", 0) <= 0) {
                long free = nextFreeOffset(network, vlan, pos, v6);
                lease.set("offset", free);
                offset.setText(String.valueOf(free));
            }
        });
        offset.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            leaseItem(items.get(pos), leaseKey)
                .set("offset", parseLong(getEditText(offset), 64));
        });
        fwd.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            leaseItem(items.get(pos), leaseKey)
                .set("forwards", parseForwards(getEditText(fwd)));
        });
        // ensure a sensible default offset exists when enabled
        var lease = leaseItem(nic.item, leaseKey);
        if (enabled && lease.optLong("offset", 0) <= 0)
            lease.set("offset", 64L);
    }

    @NonNull
    private static DataItem leaseItem(@NonNull DataItem nic, @NonNull String key) {
        var lease = nic.opt(key, null);
        if (lease == null || !lease.is(DataItem.Type.OBJECT)) {
            nic.set(key, DataItem.newObject());
            lease = nic.get(key);
        }
        return lease;
    }

    /**
     * The smallest offset >= 64 not used by any other NIC (this VM's other
     * NICs or any saved VM) on the same network/VLAN.
     */
    private long nextFreeOffset(
        @NonNull NetworkConfig network, @NonNull VlanConfig vlan, int selfPos, boolean v6
    ) {
        var used = new ArrayList<Long>();
        var netId = network.getId().toString();
        int vlanId = vlan.getVlanId();
        for (int i = 0; i < items.size(); i++) {
            if (i == selfPos) continue;
            collectOffset(used, new VMNicConfig(items.get(i)), netId, vlanId, network, v6);
        }
        var vmStore = new VMStore();
        vmStore.load(context);
        vmStore.forEach((id, vm) -> vm.forEachNic(nic ->
            collectOffset(used, nic, netId, vlanId, network, v6)));
        long candidate = 64;
        while (used.contains(candidate)) candidate++;
        return candidate;
    }

    private static void collectOffset(
        @NonNull ArrayList<Long> used, @NonNull VMNicConfig nic,
        @NonNull String netId, int vlanId, @NonNull NetworkConfig network, boolean v6
    ) {
        if (!netId.equals(nic.getNetworkId())) return;
        var nicVlan = nic.resolveDhcpVlan(network);
        if (nicVlan == null || nicVlan.getVlanId() != vlanId) return;
        if (v6) {
            if (nic.isDhcp6LeaseEnabled()) used.add(nic.getDhcp6Offset());
        } else {
            if (nic.isDhcp4LeaseEnabled()) used.add(nic.getDhcp4Offset());
        }
    }

    /** "tcp 80:80" lines <-> forwards array of {proto, host, guest}. */
    @NonNull
    static String formatForwards(@NonNull java.util.List<VMNicConfig.PortForward> forwards) {
        var sb = new StringBuilder();
        for (var fwd : forwards) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(fwd.proto).append(' ').append(fwd.host).append(':').append(fwd.guest);
        }
        return sb.toString();
    }

    @NonNull
    static DataItem parseForwards(@NonNull String text) {
        var arr = DataItem.newArray();
        for (var line : text.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            var parts = trimmed.split("\\s+", 2);
            if (parts.length != 2) continue;
            var ports = parts[1].split(":", 2);
            if (ports.length != 2) continue;
            var entry = DataItem.newObject();
            entry.set("proto", parts[0].toLowerCase());
            entry.set("host", ports[0].trim());
            entry.set("guest", ports[1].trim());
            arr.append(entry);
        }
        return arr;
    }

    private static long parseLong(@Nullable String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void showNetworkPicker(int position, @NonNull VMNetEditViewHolder holder) {
        var netStore = networks();
        netStore.load(context);
        var ids = new ArrayList<String>();
        var names = new ArrayList<String>();
        ids.add("");
        names.add(context.getString(R.string.create_vm_network_none));
        netStore.forEach((id, config) -> {
            ids.add(id.toString());
            names.add(config.getName());
        });
        var currentId = items.get(position).optString("network_id", "");
        int checked = Math.max(0, ids.indexOf(currentId));
        DialogInterface.OnClickListener onclick = (dialog, which) -> {
            items.get(position).set("network_id", ids.get(which));
            updateSelectButton(holder.btnSelect, ids.get(which));
            bindLeases(holder, new VMNicConfig(items.get(position)));
            dialog.dismiss();
        };
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.create_vm_network_label)
            .setSingleChoiceItems(names.toArray(new String[0]), checked, onclick)
            .show();
    }

    private void updateSelectButton(MaterialButton btn, @Nullable String networkId) {
        if (networkId == null || networkId.isEmpty()) {
            btn.setText(R.string.create_vm_network_none);
            return;
        }
        var net = networks().findById(networkId);
        btn.setText(net != null ? net.getName() :
            context.getString(R.string.create_vm_network_none));
    }
}
