package cn.classfun.droidvm.ui.vm.edit.storage.dir;

import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.widgets.tools.PickerButtonWidget;

public final class VMSharedDirEditViewHolder extends RecyclerView.ViewHolder {
    final TextInputEditText etPath;
    final TextInputEditText etTag;
    final TextInputEditText etTimeout;
    final PickerButtonWidget btnType;
    final PickerButtonWidget btnCache;
    final MaterialSwitch switchWriteback;
    final MaterialSwitch switchDax;
    final MaterialSwitch switchPosixAcl;
    final ImageButton btnBrowse;
    final ImageButton btnDelete;
    TextWatcher pathWatcher;
    TextWatcher tagWatcher;
    TextWatcher timeoutWatcher;

    VMSharedDirEditViewHolder(@NonNull View itemView) {
        super(itemView);
        etPath = itemView.findViewById(R.id.et_shared_dir_path);
        etTag = itemView.findViewById(R.id.et_shared_dir_tag);
        etTimeout = itemView.findViewById(R.id.et_shared_dir_timeout);
        btnType = itemView.findViewById(R.id.btn_shared_dir_type);
        btnCache = itemView.findViewById(R.id.btn_shared_dir_cache);
        switchWriteback = itemView.findViewById(R.id.switch_shared_dir_writeback);
        switchDax = itemView.findViewById(R.id.switch_shared_dir_dax);
        switchPosixAcl = itemView.findViewById(R.id.switch_shared_dir_posix_acl);
        btnBrowse = itemView.findViewById(R.id.btn_shared_dir_browse);
        btnDelete = itemView.findViewById(R.id.btn_shared_dir_delete);
    }

    void unbindWatchers() {
        if (pathWatcher != null) {
            etPath.removeTextChangedListener(pathWatcher);
            pathWatcher = null;
        }
        if (tagWatcher != null) {
            etTag.removeTextChangedListener(tagWatcher);
            tagWatcher = null;
        }
        if (timeoutWatcher != null) {
            etTimeout.removeTextChangedListener(timeoutWatcher);
            timeoutWatcher = null;
        }
    }
}