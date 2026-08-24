// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget;

public final class VMSerialEditViewHolder extends RecyclerView.ViewHolder {
    final TextView tvTitle;
    final ImageButton btnDelete;
    final PickerButtonWidget btnBackend;
    final TextInputLayout tilPath;
    final TextInputEditText etPath;
    final MaterialRadioButton radioConsole;
    final MaterialButton btnUsbSlot;
    final TextView tvNote;
    TextWatcher pathWatcher;

    VMSerialEditViewHolder(@NonNull View itemView) {
        super(itemView);
        tvTitle = itemView.findViewById(R.id.tv_serial_title);
        btnDelete = itemView.findViewById(R.id.btn_serial_delete);
        btnBackend = itemView.findViewById(R.id.btn_serial_backend);
        tilPath = itemView.findViewById(R.id.til_serial_path);
        etPath = itemView.findViewById(R.id.et_serial_path);
        radioConsole = itemView.findViewById(R.id.radio_serial_console);
        btnUsbSlot = itemView.findViewById(R.id.btn_serial_usb_slot);
        tvNote = itemView.findViewById(R.id.tv_serial_note);
    }

    void unbindWatchers() {
        if (pathWatcher != null) {
            etPath.removeTextChangedListener(pathWatcher);
            pathWatcher = null;
        }
    }
}
