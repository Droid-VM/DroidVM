package cn.classfun.droidvm.ui.network.edit;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.daemon.network.backend.UplinkResolver;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.Ipv6Source;
import cn.classfun.droidvm.lib.store.network.VlanConfig;

/**
 * Binds one VLAN card view to a VlanConfig: populates the inputs from
 * the config and reads them back into it on save.
 */
final class VlanCardBinder {
    final View view;
    private final TextView title;
    private final TextInputEditText vlanId;
    final ImageButton delete;
    private final TextInputEditText v4Cidr;
    private final MaterialSwitch v4Snat;
    private final MaterialSwitch v4Dhcp;
    private final View groupV4Dhcp;
    private final TextInputEditText v4OffsetStart;
    private final TextInputEditText v4OffsetEnd;
    private final AutoCompleteTextView v6Source;
    private final View tilV6Cidr;
    private final TextInputEditText v6Cidr;
    private final View groupV6Pd;
    private final AutoCompleteTextView pdUplink;
    private final TextInputEditText pdDuid;
    private final MaterialSwitch v6Snat;
    private final MaterialSwitch v6Dhcp;
    private final View groupV6Dhcp;
    private final TextInputEditText v6OffsetStart;
    private final TextInputEditText v6OffsetEnd;
    private final MaterialSwitch v6Slaac;
    private final View tilV4Secondary;
    private final TextInputEditText v4Secondary;
    private final View tilV6Secondary;
    private final TextInputEditText v6Secondary;
    private final TextInputEditText dns;
    private final String[] sourceLabels;

    VlanCardBinder(@NonNull View view, @NonNull List<String> uplinkNames) {
        this.view = view;
        title = view.findViewById(R.id.tv_vlan_title);
        vlanId = view.findViewById(R.id.et_vlan_id);
        delete = view.findViewById(R.id.btn_vlan_delete);
        v4Cidr = view.findViewById(R.id.et_v4_cidr);
        v4Snat = view.findViewById(R.id.sw_v4_snat);
        v4Dhcp = view.findViewById(R.id.sw_v4_dhcp);
        groupV4Dhcp = view.findViewById(R.id.group_v4_dhcp);
        v4OffsetStart = view.findViewById(R.id.et_v4_offset_start);
        v4OffsetEnd = view.findViewById(R.id.et_v4_offset_end);
        v6Source = view.findViewById(R.id.dd_v6_source);
        tilV6Cidr = view.findViewById(R.id.til_v6_cidr);
        v6Cidr = view.findViewById(R.id.et_v6_cidr);
        groupV6Pd = view.findViewById(R.id.group_v6_pd);
        pdUplink = view.findViewById(R.id.dd_pd_uplink);
        pdDuid = view.findViewById(R.id.et_pd_duid);
        v6Snat = view.findViewById(R.id.sw_v6_snat);
        v6Dhcp = view.findViewById(R.id.sw_v6_dhcp);
        groupV6Dhcp = view.findViewById(R.id.group_v6_dhcp);
        v6OffsetStart = view.findViewById(R.id.et_v6_offset_start);
        v6OffsetEnd = view.findViewById(R.id.et_v6_offset_end);
        v6Slaac = view.findViewById(R.id.sw_v6_slaac);
        tilV4Secondary = view.findViewById(R.id.til_v4_secondary);
        v4Secondary = view.findViewById(R.id.et_v4_secondary);
        tilV6Secondary = view.findViewById(R.id.til_v6_secondary);
        v6Secondary = view.findViewById(R.id.et_v6_secondary);
        dns = view.findViewById(R.id.et_dns);
        var ctx = view.getContext();
        sourceLabels = new String[]{
            ctx.getString(R.string.network_edit_vlan_v6_static),
            ctx.getString(R.string.network_edit_vlan_v6_pd),
        };
        v6Source.setAdapter(new ArrayAdapter<>(
            ctx, android.R.layout.simple_list_item_1, sourceLabels));
        v6Source.setOnItemClickListener((p, v, pos, id) -> updateV6SourceViews());
        pdUplink.setAdapter(new ArrayAdapter<>(
            ctx, android.R.layout.simple_list_item_1, uplinkNames));
        v4Dhcp.setOnCheckedChangeListener((b, checked) ->
            groupV4Dhcp.setVisibility(checked ? VISIBLE : GONE));
        v6Dhcp.setOnCheckedChangeListener((b, checked) ->
            groupV6Dhcp.setVisibility(checked ? VISIBLE : GONE));
    }

    void bind(@NonNull VlanConfig vlan, @NonNull BridgeType type) {
        var ctx = view.getContext();
        int id = vlan.getVlanId();
        title.setText(id == 0
            ? ctx.getString(R.string.network_edit_vlan_untagged)
            : ctx.getString(R.string.network_edit_vlan_title));
        vlanId.setText(String.valueOf(id));
        var net4 = vlan.getIpv4Cidr();
        v4Cidr.setText(net4 != null ? net4 : "");
        v4Snat.setChecked(vlan.isIpv4Snat());
        v4Dhcp.setChecked(
            vlan.ipv4().opt("dhcp", DataItem.newObject()).optBoolean("enabled", false));
        groupV4Dhcp.setVisibility(v4Dhcp.isChecked() ? VISIBLE : GONE);
        v4OffsetStart.setText(String.valueOf(vlan.getDhcp4OffsetStart()));
        v4OffsetEnd.setText(String.valueOf(vlan.getDhcp4OffsetEnd()));
        var source = vlan.getIpv6Source();
        v6Source.setText(
            source == Ipv6Source.DHCP_PD ? sourceLabels[1] : sourceLabels[0], false);
        var cidr6 = vlan.getIpv6Cidr();
        v6Cidr.setText(cidr6 != null ? cidr6 : "");
        var uplink = vlan.getPdUplink();
        pdUplink.setText(uplink == null || uplink.isEmpty()
            ? UplinkResolver.ID_WIFI : uplink, false);
        var duid = vlan.getPdDuid();
        pdDuid.setText(duid != null ? duid : "");
        v6Snat.setChecked(vlan.isIpv6Snat());
        v6Dhcp.setChecked(
            vlan.ipv6().opt("dhcp", DataItem.newObject()).optBoolean("enabled", false));
        groupV6Dhcp.setVisibility(v6Dhcp.isChecked() ? VISIBLE : GONE);
        v6OffsetStart.setText(String.valueOf(vlan.getDhcp6OffsetStart()));
        v6OffsetEnd.setText(String.valueOf(vlan.getDhcp6OffsetEnd()));
        v6Slaac.setChecked(
            vlan.ipv6().opt("slaac", DataItem.newObject()).optBoolean("enabled", false));
        v4Secondary.setText(String.join(", ", vlan.getIpv4Secondary()));
        v6Secondary.setText(String.join(", ", vlan.getIpv6Secondary()));
        dns.setText(String.join(", ", vlan.getDnsServers()));
        applyBridgeType(type);
        updateV6SourceViews();
    }

    /** gVisor: no DHCP-PD, no secondary networks, IPv6 SNAT allowed. */
    void applyBridgeType(@NonNull BridgeType type) {
        boolean gvisor = type == BridgeType.GVISOR;
        // a Linux bridge can't do IPv6 SNAT, but keep the stored value and
        // only gray the switch out, so flipping to Linux and back to gVisor
        // doesn't silently lose an enabled SNAT
        v6Snat.setEnabled(gvisor);
        tilV4Secondary.setVisibility(gvisor ? GONE : VISIBLE);
        tilV6Secondary.setVisibility(gvisor ? GONE : VISIBLE);
        if (gvisor && isPdSelected()) v6Source.setText(sourceLabels[0], false);
        var ctx = view.getContext();
        var labels = gvisor
            ? new String[]{sourceLabels[0]}
            : sourceLabels;
        v6Source.setAdapter(new ArrayAdapter<>(
            ctx, android.R.layout.simple_list_item_1, labels));
        updateV6SourceViews();
    }

    private boolean isPdSelected() {
        return v6Source.getText().toString().equals(sourceLabels[1]);
    }

    private void updateV6SourceViews() {
        boolean pd = isPdSelected();
        tilV6Cidr.setVisibility(pd ? GONE : VISIBLE);
        groupV6Pd.setVisibility(pd ? VISIBLE : GONE);
    }

    /** Reads the views back into the VlanConfig item. */
    void store(@NonNull VlanConfig vlan) {
        vlan.item.set("vlan_id", parseLong(textOf(vlanId), 0));
        var ipv4 = vlan.ipv4();
        ipv4.set("cidr", textOf(v4Cidr));
        ipv4.set("snat", v4Snat.isChecked());
        var dhcp4 = DataItem.newObject();
        dhcp4.set("enabled", v4Dhcp.isChecked());
        dhcp4.set("offset_start", parseLong(textOf(v4OffsetStart), 128));
        dhcp4.set("offset_end", parseLong(textOf(v4OffsetEnd), 192));
        ipv4.set("dhcp", dhcp4);
        var ipv6 = vlan.ipv6();
        ipv6.set("source",
            (isPdSelected() ? Ipv6Source.DHCP_PD : Ipv6Source.STATIC).key());
        ipv6.set("cidr", textOf(v6Cidr));
        var pd = DataItem.newObject();
        pd.set("uplink", pdUplink.getText().toString().trim());
        pd.set("duid", textOf(pdDuid));
        ipv6.set("pd", pd);
        ipv6.set("snat", v6Snat.isChecked());
        var dhcp6 = DataItem.newObject();
        dhcp6.set("enabled", v6Dhcp.isChecked());
        dhcp6.set("offset_start", parseLong(textOf(v6OffsetStart), 128));
        dhcp6.set("offset_end", parseLong(textOf(v6OffsetEnd), 192));
        ipv6.set("dhcp", dhcp6);
        var slaac = DataItem.newObject();
        slaac.set("enabled", v6Slaac.isChecked());
        ipv6.set("slaac", slaac);
        vlan.item.set("ipv4_secondary", splitList(textOf(v4Secondary)));
        vlan.item.set("ipv6_secondary", splitList(textOf(v6Secondary)));
        vlan.item.set("dns_servers", splitList(textOf(dns)));
    }

    @NonNull
    private static String textOf(@NonNull TextInputEditText et) {
        var text = et.getText();
        return text == null ? "" : text.toString().trim();
    }

    private static long parseLong(@NonNull String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @NonNull
    private static DataItem splitList(@NonNull String text) {
        var arr = DataItem.newArray();
        for (var part : text.split("[,\\s]+")) {
            var trimmed = part.trim();
            if (!trimmed.isEmpty()) arr.append(new DataItem(trimmed));
        }
        return arr;
    }

    @SuppressWarnings("unused")
    @NonNull
    static String describe(int vlanId) {
        return vlanId == 0 ? "untagged" : fmt("VLAN %d", vlanId);
    }
}
