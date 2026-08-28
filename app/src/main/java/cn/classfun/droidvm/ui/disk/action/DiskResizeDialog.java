// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.action;

import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.size.SizeUnit;
import cn.classfun.droidvm.ui.agent.AgentOperationActivity;
import cn.classfun.droidvm.ui.agent.autogrow.AutoGrowAction;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class DiskResizeDialog {
    private static final String TAG = "DiskResizeDialog";
    private final Context context;
    private long currentVirtualSize;
    private final TextView tvCurrent;
    private final TextInputRowWidget inputSize;
    private final DiskConfig config;
    private final Handler handler;
    private final TextView tvFilename;
    private final TextView tvFolder;
    private final MaterialCheckBox cbAutoGrow;

    public DiskResizeDialog(@NonNull Context context, @NonNull DiskConfig config) {
        this.context = context;
        this.config = config;
        var view = LayoutInflater.from(context).inflate(R.layout.dialog_disk_resize, null);
        inputSize = view.findViewById(R.id.input_size);
        tvCurrent = view.findViewById(R.id.tv_current_size);
        tvFilename = view.findViewById(R.id.tv_filename);
        tvFolder = view.findViewById(R.id.tv_folder);
        cbAutoGrow = view.findViewById(R.id.cb_autogrow);
        currentVirtualSize = -1;
        inputSize.setValue(1, SizeUnit.GB);
        tvCurrent.setText(R.string.disk_resize_loading);
        tvFilename.setText(R.string.disk_resize_loading);
        tvFolder.setText(R.string.disk_resize_loading);
        handler = new Handler(Looper.getMainLooper());
        var dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disk_resize_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        // Override the positive listener so validation can keep the resize dialog open.
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v ->
            dialogOkOnClick(dialog, DialogInterface.BUTTON_POSITIVE));
        runOnPool(this::asyncOperation);
    }

    private void asyncOperation() {
        long virtualSize = -1;
        try {
            var info = ImageUtils.getImageInfo(config.getFullPath());
            virtualSize = info.optLong("virtual-size", -1);
        } catch (Exception e) {
            Log.w(TAG, "Failed to get image info for resize", e);
        }
        currentVirtualSize = virtualSize;
        handler.post(this::asyncOperationUpdateUi);
    }

    private void asyncOperationUpdateUi() {
        if (currentVirtualSize > 0) {
            tvCurrent.setText(context.getString(
                R.string.disk_resize_current,
                formatSize(currentVirtualSize)
            ));
            inputSize.setValue(currentVirtualSize);
        } else {
            tvCurrent.setText(R.string.disk_resize_error_info);
        }
        tvFilename.setText(context.getString(
            R.string.disk_resize_filename,
            config.getName()
        ));
        tvFolder.setText(context.getString(
            R.string.disk_resize_folder,
            config.item.optString("folder", "")
        ));
    }

    @SuppressWarnings("unused")
    private void dialogOkOnClick(DialogInterface dialog, int which) {
        try {
            if (!inputSize.isInputValid()) {
                inputSize.setError(context.getString(R.string.disk_resize_error_invalid));
                return;
            }
            long newBytes = inputSize.getValue();
            inputSize.setError(null);
            cbAutoGrow.setError(null);
            boolean isShrink = currentVirtualSize > 0 && newBytes < currentVirtualSize;
            boolean isSameSize = currentVirtualSize > 0 && newBytes == currentVirtualSize;
            boolean autoGrow = cbAutoGrow.isChecked();
            if (isShrink && autoGrow) {
                cbAutoGrow.setError(context.getString(
                    R.string.disk_resize_error_autogrow_shrink));
                return;
            }
            if (isSameSize) {
                if (!autoGrow) {
                    inputSize.setError(context.getString(R.string.disk_resize_error_no_action));
                    return;
                }
                dialog.dismiss();
                context.startActivity(createAutoGrowIntent());
                return;
            }
            if (isShrink) {
                new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.disk_resize_shrink_title)
                    .setMessage(R.string.disk_resize_shrink_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        dialog.dismiss();
                        doResize(newBytes, true, false);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            } else {
                dialog.dismiss();
                doResize(newBytes, false, autoGrow);
            }
        } catch (Exception e) {
            inputSize.setError(context.getString(R.string.disk_resize_error_invalid));
        }
    }

    private void doResize(long bytes, boolean shrink, boolean autoGrow) {
        try {
            var obj = new JSONObject();
            obj.put("action", "resize");
            obj.put("size", String.valueOf(bytes));
            if (shrink) obj.put("shrink", true);
            var intent = DiskOperationActivity.createIntent(context, config.getId(), obj);
            if (autoGrow)
                intent.putExtra(DiskOperationActivity.EXTRA_SUCCESS_INTENT,
                    createAutoGrowIntent());
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start resize activity", e);
        }
    }

    @NonNull
    private Intent createAutoGrowIntent() {
        var agentVM = new AgentVM(VMBackend.QEMU, VMHypervisor.SOFT);
        new AutoGrowAction(agentVM);
        agentVM.addDisk(config);
        return AgentOperationActivity.createIntent(context, agentVM);
    }
}
