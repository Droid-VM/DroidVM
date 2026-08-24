// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget;

public final class VMPeripheralEditViewHolder extends RecyclerView.ViewHolder {
    final ImageView ivIcon;
    final TextView tvType;
    final TextView tvUnavailable;
    final TextView tvWarning;
    final ImageButton btnDelete;
    // virtio-snd
    final View groupVirtioSound;
    final LinearLayout soundOutEndpoints;
    final LinearLayout soundInEndpoints;
    final MaterialButton btnAddEndpoint;
    final PickerButtonWidget btnBuffer;
    final PickerButtonWidget btnUnderrun;
    // intel hda
    final View groupIntelHda;
    final MaterialButton btnHdaOut;
    final MaterialButton btnHdaIn;

    VMPeripheralEditViewHolder(@NonNull View itemView) {
        super(itemView);
        ivIcon = itemView.findViewById(R.id.iv_peripheral_icon);
        tvType = itemView.findViewById(R.id.tv_peripheral_type);
        tvUnavailable = itemView.findViewById(R.id.tv_peripheral_unavailable);
        tvWarning = itemView.findViewById(R.id.tv_peripheral_warning);
        btnDelete = itemView.findViewById(R.id.btn_peripheral_delete);
        groupVirtioSound = itemView.findViewById(R.id.group_virtio_sound);
        soundOutEndpoints = itemView.findViewById(R.id.sound_out_endpoints);
        soundInEndpoints = itemView.findViewById(R.id.sound_in_endpoints);
        btnAddEndpoint = itemView.findViewById(R.id.btn_sound_endpoint_add);
        btnBuffer = itemView.findViewById(R.id.btn_sound_buffer);
        btnUnderrun = itemView.findViewById(R.id.btn_sound_underrun);
        groupIntelHda = itemView.findViewById(R.id.group_intel_hda);
        btnHdaOut = itemView.findViewById(R.id.btn_hda_out);
        btnHdaIn = itemView.findViewById(R.id.btn_hda_in);
    }
}
