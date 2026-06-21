package cn.classfun.droidvm.ui.vm.edit.network;

import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import cn.classfun.droidvm.R;

public final class VMNetEditViewHolder extends RecyclerView.ViewHolder {
    final MaterialButton btnSelect;
    final ImageButton btnDelete;
    final TextInputEditText etMac;
    final ImageButton btnMacRandom;
    final MaterialSwitch swMacSecurity;
    final MaterialSwitch swIsolated;
    final MaterialSwitch swVlan;
    final TextInputLayout tilVlan;
    final TextInputEditText etVlan;
    final LinearLayout groupDhcp4;
    final MaterialSwitch swDhcp4;
    final View groupDhcp4Detail;
    final android.widget.TextView tvDhcp4Hint;
    final TextInputEditText etDhcp4Offset;
    final TextInputLayout tilDhcp4Offset;
    final TextInputLayout tilFwd4;
    final TextInputEditText etFwd4;
    final LinearLayout groupDhcp6;
    final MaterialSwitch swDhcp6;
    final View groupDhcp6Detail;
    final android.widget.TextView tvDhcp6Hint;
    final TextInputEditText etDhcp6Offset;
    final TextInputLayout tilDhcp6Offset;
    final TextInputLayout tilFwd6;
    final TextInputEditText etFwd6;

    VMNetEditViewHolder(@NonNull View itemView) {
        super(itemView);
        btnSelect = itemView.findViewById(R.id.btn_net_select);
        btnDelete = itemView.findViewById(R.id.btn_net_delete);
        etMac = itemView.findViewById(R.id.et_net_mac);
        btnMacRandom = itemView.findViewById(R.id.btn_net_mac_random);
        swMacSecurity = itemView.findViewById(R.id.sw_nic_mac_security);
        swIsolated = itemView.findViewById(R.id.sw_nic_isolated);
        swVlan = itemView.findViewById(R.id.sw_nic_vlan);
        tilVlan = itemView.findViewById(R.id.til_nic_vlan);
        etVlan = itemView.findViewById(R.id.et_nic_vlan);
        groupDhcp4 = itemView.findViewById(R.id.group_nic_dhcp4);
        swDhcp4 = itemView.findViewById(R.id.sw_nic_dhcp4);
        groupDhcp4Detail = itemView.findViewById(R.id.group_nic_dhcp4_detail);
        tvDhcp4Hint = itemView.findViewById(R.id.tv_nic_dhcp4_hint);
        etDhcp4Offset = itemView.findViewById(R.id.et_nic_dhcp4_offset);
        tilDhcp4Offset = itemView.findViewById(R.id.til_nic_dhcp4_offset);
        tilFwd4 = itemView.findViewById(R.id.til_nic_fwd4);
        etFwd4 = itemView.findViewById(R.id.et_nic_fwd4);
        groupDhcp6 = itemView.findViewById(R.id.group_nic_dhcp6);
        swDhcp6 = itemView.findViewById(R.id.sw_nic_dhcp6);
        groupDhcp6Detail = itemView.findViewById(R.id.group_nic_dhcp6_detail);
        tvDhcp6Hint = itemView.findViewById(R.id.tv_nic_dhcp6_hint);
        etDhcp6Offset = itemView.findViewById(R.id.et_nic_dhcp6_offset);
        tilDhcp6Offset = itemView.findViewById(R.id.til_nic_dhcp6_offset);
        tilFwd6 = itemView.findViewById(R.id.til_nic_fwd6);
        etFwd6 = itemView.findViewById(R.id.et_nic_fwd6);
    }
}
