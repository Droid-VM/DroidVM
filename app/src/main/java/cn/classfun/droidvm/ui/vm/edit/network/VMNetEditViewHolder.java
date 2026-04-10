package cn.classfun.droidvm.ui.vm.edit.network;

import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;

public final class VMNetEditViewHolder extends RecyclerView.ViewHolder {
    final MaterialButton btnSelect;
    final ImageButton btnDelete;
    final TextInputEditText etMac;
    final ImageButton btnMacRandom;

    VMNetEditViewHolder(@NonNull View itemView) {
        super(itemView);
        btnSelect = itemView.findViewById(R.id.btn_net_select);
        btnDelete = itemView.findViewById(R.id.btn_net_delete);
        etMac = itemView.findViewById(R.id.et_net_mac);
        btnMacRandom = itemView.findViewById(R.id.btn_net_mac_random);
    }
}
