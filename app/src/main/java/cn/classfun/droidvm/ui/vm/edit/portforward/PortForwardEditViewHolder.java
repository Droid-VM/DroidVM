package cn.classfun.droidvm.ui.vm.edit.portforward;

import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget;

public final class PortForwardEditViewHolder extends RecyclerView.ViewHolder {
    final TextInputEditText etHostPort;
    final TextInputEditText etGuestPort;
    final TextInputEditText etHostIp;
    final TextInputEditText etGuestIp;
    final PickerButtonWidget btnProtocol;
    final MaterialSwitch switchEnabled;
    final MaterialButton btnAdvanced;
    final MaterialButton btnNetwork;
    final ImageButton btnDelete;
    final LinearLayout layoutAdvanced;
    TextWatcher hostPortWatcher;
    TextWatcher guestPortWatcher;
    TextWatcher hostIpWatcher;
    TextWatcher guestIpWatcher;

    PortForwardEditViewHolder(@NonNull View itemView) {
        super(itemView);
        etHostPort = itemView.findViewById(R.id.et_pf_host_port);
        etGuestPort = itemView.findViewById(R.id.et_pf_guest_port);
        etHostIp = itemView.findViewById(R.id.et_pf_host_ip);
        etGuestIp = itemView.findViewById(R.id.et_pf_guest_ip);
        btnProtocol = itemView.findViewById(R.id.btn_pf_protocol);
        switchEnabled = itemView.findViewById(R.id.switch_pf_enabled);
        btnAdvanced = itemView.findViewById(R.id.btn_pf_advanced);
        btnNetwork = itemView.findViewById(R.id.btn_pf_network);
        btnDelete = itemView.findViewById(R.id.btn_pf_delete);
        layoutAdvanced = itemView.findViewById(R.id.layout_pf_advanced);
    }

    void unbindWatchers() {
        if (hostPortWatcher != null) {
            etHostPort.removeTextChangedListener(hostPortWatcher);
            hostPortWatcher = null;
        }
        if (guestPortWatcher != null) {
            etGuestPort.removeTextChangedListener(guestPortWatcher);
            guestPortWatcher = null;
        }
        if (hostIpWatcher != null) {
            etHostIp.removeTextChangedListener(hostIpWatcher);
            hostIpWatcher = null;
        }
        if (guestIpWatcher != null) {
            etGuestIp.removeTextChangedListener(guestIpWatcher);
            guestIpWatcher = null;
        }
    }
}
