// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import cn.classfun.droidvm.R;

public final class UsbRuleViewHolder extends RecyclerView.ViewHolder {
    /** Everything the card says, which is what fades when the rule cannot be reached. */
    final View content;
    /** The three lines of the left column, top to bottom; see {@link UsbRuleLines}. */
    final TextView[] lines;
    /** Shown in edit mode; a touch on it starts the drag rather than waiting for a long press. */
    final ImageView ivDrag;
    final ImageButton btnDelete;
    final MaterialButton btnTarget;
    final TextView tvWarning;

    UsbRuleViewHolder(@NonNull View itemView) {
        super(itemView);
        content = itemView.findViewById(R.id.rule_content);
        lines = new TextView[]{
            itemView.findViewById(R.id.tv_rule_line1),
            itemView.findViewById(R.id.tv_rule_line2),
            itemView.findViewById(R.id.tv_rule_line3),
        };
        ivDrag = itemView.findViewById(R.id.iv_rule_drag);
        btnDelete = itemView.findViewById(R.id.btn_rule_delete);
        btnTarget = itemView.findViewById(R.id.btn_rule_target);
        tvWarning = itemView.findViewById(R.id.tv_rule_warning);
    }
}
