package cn.classfun.droidvm.ui.disk.action;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.createIntent;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class DiskCloneDialog {
    private static final String TAG = "DiskCloneDialog";
    private final Context context;
    private final DiskConfig config;

    public DiskCloneDialog(@NonNull Context context, @NonNull DiskConfig config) {
        this.context = context;
        this.config = config;
    }

    public void show() {
        var view = LayoutInflater.from(context).inflate(R.layout.dialog_disk_clone, null);
        TextInputRowWidget inputName = view.findViewById(R.id.input_name);
        TextInputRowWidget inputFolder = view.findViewById(R.id.input_folder);
        inputName.setText(config.getName());
        inputFolder.setText(config.item.optString("folder", ""));
        var dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disk_clone_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            var name = inputName.getText();
            var folder = inputFolder.getText();
            boolean valid = true;
            inputName.setError(null);
            inputFolder.setError(null);
            if (name.isEmpty()) {
                inputName.setError(context.getString(R.string.disk_clone_error_name_empty));
                valid = false;
            }
            if (folder.isEmpty()) {
                inputFolder.setError(context.getString(R.string.disk_clone_error_folder_empty));
                valid = false;
            }
            if (shellCheckExists(pathJoin(folder, name))) {
                inputName.setError(context.getString(R.string.disk_clone_error_file_exists));
                valid = false;
            }
            if (!valid) return;
            dialog.dismiss();
            doClone(folder, name);
        });
    }

    private void doClone(@NonNull String folder, @NonNull String name) {
        try {
            var output = pathJoin(folder, name);
            var obj = new JSONObject();
            obj.put("action", "clone");
            obj.put("output", output);
            var intent = createIntent(context, config.getId(), obj);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start clone operation", e);
        }
    }
}
