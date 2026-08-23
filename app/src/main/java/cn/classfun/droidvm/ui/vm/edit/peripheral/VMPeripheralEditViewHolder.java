// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import cn.classfun.droidvm.R;

public final class VMPeripheralEditViewHolder extends RecyclerView.ViewHolder {
    final ImageView ivIcon;
    final TextView tvType;
    final TextView tvWarning;
    final MaterialButton btnHost;
    final ImageButton btnDelete;

    VMPeripheralEditViewHolder(@NonNull View itemView) {
        super(itemView);
        ivIcon = itemView.findViewById(R.id.iv_peripheral_icon);
        tvType = itemView.findViewById(R.id.tv_peripheral_type);
        tvWarning = itemView.findViewById(R.id.tv_peripheral_warning);
        btnHost = itemView.findViewById(R.id.btn_peripheral_host);
        btnDelete = itemView.findViewById(R.id.btn_peripheral_delete);
    }
}
