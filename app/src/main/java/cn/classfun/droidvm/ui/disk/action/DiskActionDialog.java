package cn.classfun.droidvm.ui.disk.action;

import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.dirname;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.createIntent;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.agent.password.ChangePasswordActivity;
import cn.classfun.droidvm.ui.disk.create.DiskCreateActivity;
import cn.classfun.droidvm.ui.disk.download.ImportURLActivity;
import cn.classfun.droidvm.ui.disk.images.ImportImagesActivity;
import cn.classfun.droidvm.ui.disk.lxc.ImportLxcImagesActivity;

public final class DiskActionDialog {
    private final static String TAG = "DiskActionDialog";
    private final Handler mainLooper = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> activityLauncher;
    private final Context context;
    private final Runnable onUpdate;
    private final Runnable filePicker;

    public DiskActionDialog(
        @NonNull Context context,
        @Nullable Runnable onUpdate,
        @Nullable Runnable filePicker
    ) {
        this(context, onUpdate, filePicker, null);
    }

    public DiskActionDialog(
        @NonNull Context context,
        @Nullable Runnable onUpdate,
        @Nullable Runnable filePicker,
        @Nullable ActivityResultLauncher<Intent> activityLauncher
    ) {
        this.context = context;
        this.onUpdate = onUpdate;
        this.filePicker = filePicker;
        this.activityLauncher = activityLauncher;
    }

    public boolean diskMenuOnClick(@NonNull DiskConfig config, @IdRes int id) {
        if (id == R.id.menu_disk_resize) {
            new DiskResizeDialog(context, config);
            return true;
        } else if (id == R.id.menu_disk_convert) {
            var convert = new DiskSetFormatDialog(context, config);
            convert.show();
        } else if (id == R.id.menu_disk_optimize) {
            tryOptimize(config);
            return true;
        } else if (id == R.id.menu_disk_delete) {
            confirmDelete(config);
            return true;
        } else if (id == R.id.menu_disk_create_increment) {
            var intent = new Intent(context, DiskCreateActivity.class);
            intent.putExtra(DiskCreateActivity.EXTRA_BACKING_ID, config.getId().toString());
            launchActivity(intent);
            return true;
        } else if (id == R.id.menu_disk_show_info) {
            showMoreInfo(config);
            return true;
        } else if (id == R.id.menu_disk_clone) {
            new DiskCloneDialog(context, config).show();
            return true;
        } else if (id == R.id.menu_disk_change_password) {
            var intent = ChangePasswordActivity.createIntent(context, config.getId());
            launchActivity(intent);
            return true;
        }
        return false;
    }

    public void tryOptimize(@NonNull DiskConfig config) {
        Consumer<JSONObject> invoke = obj -> {
            try {
                var intent = createIntent(context, config.getId(), obj);
                context.startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start optimize activity", e);
            }
        };
        runOnPool(() -> {
            var obj = new JSONObject();
            try {
                var info = ImageUtils.getImageInfo(config.getFullPath());
                obj.put("action", "convert");
                obj.put("format", info.getString("format"));
                if (info.has("backing-filename"))
                    obj.put("backing_path", info.getString("backing-filename"));
            } catch (Exception e) {
                Log.w(TAG, "Failed to optimize", e);
                mainLooper.post(() ->
                    Toast.makeText(context, e.getMessage(), LENGTH_SHORT).show());
                return;
            }
            if (!obj.has("backing_path")) {
                invoke.accept(obj);
                return;
            }
            mainLooper.post(() -> new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.disk_optimize_backing_title)
                .setMessage(R.string.disk_optimize_backing_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.disk_optimize_backing_btn_keep, (d, w) ->
                    invoke.accept(obj))
                .setNeutralButton(R.string.disk_optimize_backing_btn_flatten, (d, w) -> {
                    obj.remove("backing_path");
                    invoke.accept(obj);
                })
                .show());
        });
    }

    private void showMoreInfo(@NonNull DiskConfig config) {
        runOnPool(() -> {
            final String infos;
            try {
                var result = runList(
                    getPrebuiltBinaryPath("qemu-img"),
                    "info", config.getFullPath()
                );
                if (!result.isSuccess()) {
                    result.printLog("qemu-img");
                    throw new RuntimeException(fmt("qemu-img failed: %d", result.getCode()));
                }
                infos = String.join("\n", result.getOutString()).trim();
            } catch (Exception e) {
                Log.w(TAG, "Failed to read image info", e);
                mainLooper.post(() ->
                    Toast.makeText(context, e.getMessage(), LENGTH_SHORT).show());
                return;
            }
            mainLooper.post(() -> {
                var inf = LayoutInflater.from(context);
                var view = inf.inflate(R.layout.dialog_logs, null);
                TextView tvLog = view.findViewById(R.id.tv_log);
                tvLog.setText(infos);
                new MaterialAlertDialogBuilder(context)
                    .setTitle(config.getName())
                    .setView(view)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            });
        });
    }

    public void showImportDialog() {
        MenuDialogBuilder.showSimple(
            context,
            R.string.disk_add_title,
            R.menu.menu_disk_add,
            this::onImportItemSelected
        );
    }

    public boolean onImportItemSelected(@NonNull MenuItem item) {
        var id = item.getItemId();
        if (id == R.id.menu_disk_add_import) {
            if (filePicker != null) filePicker.run();
            return false;
        }
        Class<? extends Activity> target;
        if (id == R.id.menu_disk_add_url) {
            target = ImportURLActivity.class;
        } else if (id == R.id.menu_disk_add_images) {
            target = ImportImagesActivity.class;
        } else if (id == R.id.menu_disk_add_lxc) {
            target = ImportLxcImagesActivity.class;
        } else if (id == R.id.menu_disk_add_create) {
            target = DiskCreateActivity.class;
        } else return false;
        launchActivity(new Intent(context, target));
        return true;
    }

    public DiskConfig onFileImported(Uri uri) {
        if (uri == null) return null;
        var path = resolveUriPath(context, uri);
        if (path == null || path.isEmpty()) {
            Toast.makeText(context, R.string.disk_create_error_folder_invalid, LENGTH_SHORT).show();
            return null;
        }
        if (!path.startsWith("/") || !path.contains("/")) {
            Toast.makeText(context, R.string.disk_create_error_folder_invalid, LENGTH_SHORT).show();
            return null;
        }
        var fileName = basename(path);
        var folder = dirname(path);
        var config = new DiskConfig();
        config.setName(fileName);
        config.item.set("folder", folder);
        var store = new DiskStore();
        runOnPool(() -> {
            store.load(context);
            store.add(config);
            store.save(context);
            if (this.onUpdate != null)
                this.onUpdate.run();
        });
        var str = context.getString(R.string.disk_create_success, fileName);
        Toast.makeText(context, str, LENGTH_SHORT).show();
        return config;
    }

    private void launchActivity(@NonNull Intent intent) {
        if (activityLauncher != null)
            activityLauncher.launch(intent);
        else
            context.startActivity(intent);
    }

    public void confirmDelete(@NonNull DiskConfig config) {
        var layout = new LinearLayout(context);
        var checkBox = new CheckBox(context);
        checkBox.setText(R.string.disk_delete_file);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, 0, pad, 0);
        layout.addView(checkBox);
        DialogInterface.OnClickListener onclick = (d, w) -> {
            boolean isChecked = checkBox.isChecked();
            var store = new DiskStore();
            runOnPool(() -> {
                if (isChecked) runList("rm", "-f", config.getFullPath());
                store.load(context);
                store.removeById(config.getId());
                store.save(context);
                if (this.onUpdate != null)
                    this.onUpdate.run();
            });
        };
        new MaterialAlertDialogBuilder(context)
            .setTitle(config.getName())
            .setMessage(R.string.disk_delete_confirm)
            .setView(layout)
            .setPositiveButton(R.string.vm_delete, onclick)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
