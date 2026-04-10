package cn.classfun.droidvm.ui.vm.edit.storage.disk;

import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget;

public final class VMDiskEditViewHolder extends RecyclerView.ViewHolder {
    final TextInputEditText etPath;
    final PickerButtonWidget btnBus;
    final MaterialSwitch switchReadonly;
    final ImageButton btnBrowse;
    final ImageButton btnDelete;
    TextWatcher pathWatcher;

    VMDiskEditViewHolder(@NonNull View itemView) {
        super(itemView);
        etPath = itemView.findViewById(R.id.et_disk_path);
        btnBus = itemView.findViewById(R.id.btn_bus);
        switchReadonly = itemView.findViewById(R.id.switch_readonly);
        btnBrowse = itemView.findViewById(R.id.btn_browse);
        btnDelete = itemView.findViewById(R.id.btn_delete);
    }

    void unbindWatcher() {
        if (pathWatcher != null) {
            etPath.removeTextChangedListener(pathWatcher);
            pathWatcher = null;
        }
    }
}
