// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import cn.classfun.droidvm.R;

public final class UsbRuleViewHolder extends RecyclerView.ViewHolder {
    final TextView tvIndex;
    final TextView tvTitle;
    final TextView tvSubtitle;
    final TextView tvTarget;
    final ImageButton btnUp;
    final ImageButton btnDown;
    final ImageButton btnDelete;

    UsbRuleViewHolder(@NonNull View itemView) {
        super(itemView);
        tvIndex = itemView.findViewById(R.id.tv_rule_index);
        tvTitle = itemView.findViewById(R.id.tv_rule_title);
        tvSubtitle = itemView.findViewById(R.id.tv_rule_subtitle);
        tvTarget = itemView.findViewById(R.id.tv_rule_target);
        btnUp = itemView.findViewById(R.id.btn_rule_up);
        btnDown = itemView.findViewById(R.id.btn_rule_down);
        btnDelete = itemView.findViewById(R.id.btn_rule_delete);
    }
}
