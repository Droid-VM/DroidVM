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
import cn.classfun.droidvm.lib.store.network.UplinkMode;
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
        boolean linux = isLinuxBridge(nic);
        holder.swVlan.setOnCheckedChangeListener(null);
        holder.swVlan.setChecked(vlanId != null);
        applyVlanFieldState(holder, vlanId);
        validateVlanField(holder, linux, vlanId == null ? -1 : vlanId);
        holder.swVlan.setOnCheckedChangeListener((b, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (checked) {
                long id = parseLong(getEditText(holder.etVlan), 0);
                if (linux) {
                    // a Linux bridge has no untagged-only ports: the 4095
                    // trunk marker or a leftover 0 become a tagged default
                    if (id == 4095 || id == 0) id = defaultTaggedVlan(nic);
                } else if (id == 4095) {
                    id = 0;  // gvisor: leaving trunk starts at untagged
                }
                items.get(pos).set("vlan_id", id);
                applyVlanFieldState(holder, (int) id);
                validateVlanField(holder, linux, id);
            } else {
                items.get(pos).remove("vlan_id");
                applyVlanFieldState(holder, null);
                holder.tilVlan.setError(null);
            }
            bindLeases(holder, new VMNicConfig(items.get(pos)));
        });
        holder.etVlan.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (holder.swVlan.isChecked()) {
                long id = parseLong(getEditText(holder.etVlan), 0);
                if (id == 4095) {
                    // typing the trunk marker means trunk: same as toggling
                    // off (the listener clears vlan_id and locks the field)
                    holder.swVlan.setChecked(false);
                    return;
                }
                items.get(pos).set("vlan_id", id);
                validateVlanField(holder, linux, id);
            }
            bindLeases(holder, new VMNicConfig(items.get(pos)));
        });
    }

    private boolean isLinuxBridge(@NonNull VMNicConfig nic) {
        var netId = nic.getNetworkId();
        var network = netId != null ? networks().findById(netId) : null;
        return network != null && network.getBridgeType()
            == cn.classfun.droidvm.lib.store.network.BridgeType.LINUX;
    }

    /** First tagged VLAN configured on the NIC's network, or 1. */
    private int defaultTaggedVlan(@NonNull VMNicConfig nic) {
        var netId = nic.getNetworkId();
        var network = netId != null ? networks().findById(netId) : null;
        if (network != null)
            for (var vlan : network.getVlans()) {
                int v = vlan.getVlanId();
                if (v >= 1 && v <= 4094) return v;
            }
        return 1;
    }

    private void validateVlanField(
        @NonNull VMNetEditViewHolder holder, boolean linux, long id
    ) {
        String error = null;
        if (linux && id == 0)
            error = context.getString(R.string.edit_vm_net_vlan_no_untagged);
        else if (id > 4094)
            error = context.getString(R.string.edit_vm_net_vlan_invalid);
        holder.tilVlan.setError(error);
    }

    /** Toggle off = trunk: the ID field locks to the 4095 trunk marker. */
    private static void applyVlanFieldState(
        @NonNull VMNetEditViewHolder holder, @Nullable Integer vlanId
    ) {
        if (vlanId == null) {
            holder.etVlan.setText("4095");
            holder.etVlan.setEnabled(false);
            holder.tilVlan.setEnabled(false);
        } else {
            holder.etVlan.setEnabled(true);
            holder.tilVlan.setEnabled(true);
            holder.etVlan.setText(String.valueOf(vlanId));
        }
    }

    /**
     * DHCP lease sections are visible for every L3 network; the toggle is
     * locked until the effective VLAN actually serves that DHCP family.
     */
    private void bindLeases(@NonNull VMNetEditViewHolder holder, @NonNull VMNicConfig nic) {
        var netId = nic.getNetworkId();
        var network = netId != null ? networks().findById(netId) : null;
        boolean l3 = network != null && network.getUplinkMode() == UplinkMode.L3;
        holder.groupDhcp4.setVisibility(l3 ? VISIBLE : GONE);
        holder.groupDhcp6.setVisibility(l3 ? VISIBLE : GONE);
        if (!l3) return;
        var vlan = nic.resolveDhcpVlan(network);
        bindLease(holder, nic, vlan, network, false);
        bindLease(holder, nic, vlan, network, true);
    }

    private void bindLease(
        @NonNull VMNetEditViewHolder holder, @NonNull VMNicConfig nic,
        @Nullable VlanConfig vlan, @NonNull NetworkConfig network, boolean v6
    ) {
        var sw = v6 ? holder.swDhcp6 : holder.swDhcp4;
        var detail = v6 ? holder.groupDhcp6Detail : holder.groupDhcp4Detail;
        var hint = v6 ? holder.tvDhcp6Hint : holder.tvDhcp4Hint;
        var offset = v6 ? holder.etDhcp6Offset : holder.etDhcp4Offset;
        var offsetTil = v6 ? holder.tilDhcp6Offset : holder.tilDhcp4Offset;
        var fwdTil = v6 ? holder.tilFwd6 : holder.tilFwd4;
        var fwd = v6 ? holder.etFwd6 : holder.etFwd4;
        var leaseKey = v6 ? "dhcp6_lease" : "dhcp4_lease";
        boolean dhcpServed = vlan != null
            && (v6 ? vlan.isDhcp6Enabled() : vlan.isDhcp4Enabled());
        boolean enabled = dhcpServed
            && (v6 ? nic.isDhcp6LeaseEnabled() : nic.isDhcp4LeaseEnabled());
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(enabled);
        sw.setEnabled(dhcpServed);
        detail.setVisibility(enabled ? VISIBLE : GONE);
        offset.setEnabled(enabled);
        offsetTil.setEnabled(enabled);
        if (dhcpServed) {
            hint.setVisibility(GONE);
        } else {
            // tell the user which network/VLAN needs DHCP enabled first
            int effectiveVlan = nic.getVlanId() != null ? nic.getVlanId() : 0;
            var msg = context.getString(
                R.string.edit_vm_net_dhcp_need_enable,
                v6 ? "6" : "4", network.getName(), effectiveVlan);
            if (nic.getVlanId() == null)
                msg += context.getString(R.string.edit_vm_net_dhcp_trunk_note);
            hint.setText(msg);
            hint.setVisibility(VISIBLE);
        }
        long currentOffset = v6 ? nic.getDhcp6Offset() : nic.getDhcp4Offset();
        offset.setText(String.valueOf(currentOffset));
        fwd.setText(formatForwards(
            v6 ? nic.getDhcp6Forwards() : nic.getDhcp4Forwards()));
        // IPv6 forwards need IPv6 SNAT, which the Linux bridge can't do
        // (Android kernel has no IPv6 NAT) — hide them there entirely
        boolean fwdSupported = !v6
            || network.getBridgeType() == cn.classfun.droidvm.lib.store.network.BridgeType.GVISOR;
        fwdTil.setVisibility(fwdSupported ? VISIBLE : GONE);
        boolean snat = fwdSupported && vlan != null
            && (v6 ? vlan.isIpv6Snat() : vlan.isIpv4Snat());
        fwdTil.setEnabled(snat);
        fwd.setEnabled(snat);
        fwdTil.setHelperText(snat ? context.getString(R.string.edit_vm_net_forwards_hint)
            : context.getString(R.string.edit_vm_net_forwards_need_snat));
        var vlanFinal = vlan;
        sw.setOnCheckedChangeListener((b, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            detail.setVisibility(checked ? VISIBLE : GONE);
            offset.setEnabled(checked);
            offsetTil.setEnabled(checked);
            var lease = leaseItem(items.get(pos), leaseKey);
            lease.set("enabled", checked);
            if (checked && lease.optLong("offset", 0) <= 0 && vlanFinal != null) {
                long free = nextFreeOffset(network, vlanFinal, pos, v6);
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
        // normalize: a lease can't stay enabled once its DHCP is gone
        var lease = leaseItem(nic.item, leaseKey);
        if (!dhcpServed && lease.optBoolean("enabled", false))
            lease.set("enabled", false);
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
            if (!fwd.proto.equals("any"))
                sb.append(fwd.proto).append(' ');
            sb.append(fwd.host).append(':').append(fwd.guest);
        }
        return sb.toString();
    }

    @NonNull
    static DataItem parseForwards(@NonNull String text) {
        var arr = DataItem.newArray();
        for (var line : text.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // accept "proto host:guest" or just "host:guest" (proto = any = tcp+udp)
            String proto = "any";
            String spec = trimmed;
            var parts = trimmed.split("\\s+", 2);
            if (parts.length == 2) {
                var first = parts[0].toLowerCase();
                if (first.equals("tcp") || first.equals("udp")) {
                    proto = first;
                    spec = parts[1].trim();
                }
            }
            var ports = spec.split(":", 2);
            if (ports.length != 2) continue;
            var host = ports[0].trim();
            var guest = ports[1].trim();
            if (host.isEmpty() || guest.isEmpty()) continue;
            var entry = DataItem.newObject();
            entry.set("proto", proto);
            entry.set("host", host);
            entry.set("guest", guest);
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
