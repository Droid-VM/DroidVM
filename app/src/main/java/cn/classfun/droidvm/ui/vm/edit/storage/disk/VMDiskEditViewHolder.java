// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.storage.disk;

import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget;

public final class VMDiskEditViewHolder extends RecyclerView.ViewHolder {
    final TextInputEditText etPath;
    final TextInputEditText etOptions;
    final PickerButtonWidget btnBus;
    final MaterialSwitch switchReadonly;
    final ImageButton btnBrowse;
    final ImageButton btnDelete;
    final MaterialButton btnBranches;
    TextWatcher pathWatcher;
    TextWatcher optionsWatcher;

    VMDiskEditViewHolder(@NonNull View itemView) {
        super(itemView);
        etPath = itemView.findViewById(R.id.et_disk_path);
        etOptions = itemView.findViewById(R.id.et_disk_options);
        btnBus = itemView.findViewById(R.id.btn_bus);
        switchReadonly = itemView.findViewById(R.id.switch_readonly);
        btnBrowse = itemView.findViewById(R.id.btn_browse);
        btnDelete = itemView.findViewById(R.id.btn_delete);
        btnBranches = itemView.findViewById(R.id.btn_branches);
    }

    void unbindWatchers() {
        if (pathWatcher != null) {
            etPath.removeTextChangedListener(pathWatcher);
            pathWatcher = null;
        }
        if (optionsWatcher != null) {
            etOptions.removeTextChangedListener(optionsWatcher);
            optionsWatcher = null;
        }
    }
}
