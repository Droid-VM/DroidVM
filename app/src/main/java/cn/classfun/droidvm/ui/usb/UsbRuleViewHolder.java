// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import cn.classfun.droidvm.R;

public final class UsbRuleViewHolder extends RecyclerView.ViewHolder {
    /** Everything the card says, which is what fades when the rule cannot be reached. */
    final View content;
    final TextView tvTitle;
    final ImageButton btnUp;
    final ImageButton btnDown;
    final ImageButton btnDelete;
    /** The id and port rows, shown only in the layers whose rules carry those fields. */
    final LinearLayout rowId;
    final LinearLayout rowPort;
    final MaterialButton btnId;
    final MaterialButton btnPort;
    final MaterialButton btnTarget;
    final TextView tvWarning;

    UsbRuleViewHolder(@NonNull View itemView) {
        super(itemView);
        content = itemView.findViewById(R.id.rule_content);
        tvTitle = itemView.findViewById(R.id.tv_rule_title);
        btnUp = itemView.findViewById(R.id.btn_rule_up);
        btnDown = itemView.findViewById(R.id.btn_rule_down);
        btnDelete = itemView.findViewById(R.id.btn_rule_delete);
        rowId = itemView.findViewById(R.id.row_rule_id);
        rowPort = itemView.findViewById(R.id.row_rule_port);
        btnId = itemView.findViewById(R.id.btn_rule_id);
        btnPort = itemView.findViewById(R.id.btn_rule_port);
        btnTarget = itemView.findViewById(R.id.btn_rule_target);
        tvWarning = itemView.findViewById(R.id.tv_rule_warning);
    }
}
