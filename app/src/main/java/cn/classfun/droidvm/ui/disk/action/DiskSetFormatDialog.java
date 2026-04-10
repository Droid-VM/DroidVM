package cn.classfun.droidvm.ui.disk.action;

import static android.widget.Toast.LENGTH_LONG;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.stripExtension;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.startConvert;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class DiskSetFormatDialog {
    private static final String TAG = "DiskSetFormatDialog";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final DiskConfig config;
    private TextInputRowWidget inputName;
    private ChooseRowWidget chooseFormat;
    private TextView tvCurrentFormat;
    private DiskFormat currentFormat = null;

    public DiskSetFormatDialog(@NonNull Context context, @NonNull DiskConfig config) {
        this.context = context;
        this.config = config;
    }

    public void show() {
        var view = LayoutInflater.from(context).inflate(R.layout.dialog_disk_set_format, null);
        tvCurrentFormat = view.findViewById(R.id.tv_current_format);
        inputName = view.findViewById(R.id.input_name);
        chooseFormat = view.findViewById(R.id.choose_format);
        chooseFormat.setItems(DiskFormat.class);
        inputName.setText(config.getName());
        tvCurrentFormat.setText(R.string.disk_set_format_loading);
        runOnPool(this::loadImageInfo);
        chooseFormat.setOnValueChangedListener(() -> {
            DiskFormat fmt = chooseFormat.getSelectedItem();
            updateFileName(inputName, config.getName(), fmt.getExt());
        });
        var dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.disk_set_format_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            var name = inputName.getText();
            if (name.isEmpty()) {
                inputName.setError(context.getString(R.string.disk_set_format_error_name));
                return;
            }
            inputName.setError(null);
            DiskFormat format = chooseFormat.getSelectedItem();
            if (format.equals(currentFormat)) {
                Toast.makeText(context, R.string.disk_set_format_error_same, LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            var output = pathJoin(config.item.optString("folder", ""), name);
            startConvert(context, config.getId(), format.name().toLowerCase(), output);
        });
    }

    private void loadImageInfo() {
        try {
            currentFormat = null;
            var info = ImageUtils.getImageInfo(config.getFullPath());
            var detectedFormat = info.optString("format", null);
            currentFormat = DiskFormat.valueOf(detectedFormat.toUpperCase());
        } catch (Exception e) {
            Log.w(TAG, "Failed to get image info", e);
        }
        handler.post(() -> {
            if (currentFormat != null) {
                tvCurrentFormat.setText(context.getString(
                    R.string.disk_set_format_current, currentFormat.name()
                ));
                chooseFormat.setSelectedItem(currentFormat);
            } else {
                tvCurrentFormat.setText(R.string.disk_set_format_error_info);
                currentFormat = chooseFormat.getSelectedItem();
            }
            updateFileName(inputName, config.getName(), currentFormat.getExt());
        });
    }

    private static void updateFileName(
        @NonNull TextInputRowWidget inputName,
        String originalName,
        String newExt
    ) {
        var baseName = stripExtension(originalName);
        var fn = fmt("%s.%s", baseName, newExt);
        inputName.setText(fn);
        var text = inputName.getText();
        inputName.setSelection(text.length());
    }
}
