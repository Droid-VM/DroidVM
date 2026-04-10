package cn.classfun.droidvm.ui.vm.edit.boot;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_KERNEL;
import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;
import static cn.classfun.droidvm.lib.Constants.PATH_MICRODROID_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_MICRODROID_INITRD_DEBUG;
import static cn.classfun.droidvm.lib.Constants.PATH_MICRODROID_KERNEL;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellAsyncCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.checkFilePath;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;

import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.widgets.container.CollapsibleContainer;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class VMEditBootTab extends VMEditBaseTab {
    private SwitchRowWidget swAutoUp;
    private SwitchRowWidget swUefi;
    private TextView tvUefiHint;
    private CollapsibleContainer containerKernel;
    private TextInputRowWidget inputKernel;
    private TextInputRowWidget inputInitrd;
    private TextInputRowWidget inputCmdline;

    public VMEditBootTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        swAutoUp = view.findViewById(R.id.sw_auto_up);
        swUefi = view.findViewById(R.id.sw_uefi);
        tvUefiHint = view.findViewById(R.id.tv_uefi_hint);
        containerKernel = view.findViewById(R.id.container_kernel);
        inputKernel = view.findViewById(R.id.input_kernel);
        inputInitrd = view.findViewById(R.id.input_initrd);
        inputCmdline = view.findViewById(R.id.input_cmdline);
    }

    @Override
    public void initValue() {
        inputKernel.setIconButtonOnClickListener(this::showKernelBrowseDialog);
        inputInitrd.setIconButtonOnClickListener(this::showInitrdBrowseDialog);
        swUefi.setOnCheckedChangeListener((btn, checked) -> setUefiMode(checked));
    }

    private void setUefiMode(boolean enabled) {
        containerKernel.setVisibility(enabled ? GONE : VISIBLE);
        tvUefiHint.setVisibility(enabled ? VISIBLE : GONE);
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        var item = config.item;
        boolean isUefi = true;
        var kernel = item.optString("kernel", "");
        var initrd = item.optString("initrd", "");
        var cmdline = item.optString("cmdline", "");
        if (!kernel.equals(PATH_EDK2_FIRMWARE)) isUefi = false;
        if (!initrd.isEmpty()) isUefi = false;
        if (!cmdline.isEmpty()) isUefi = false;
        swUefi.setChecked(isUefi);
        setUefiMode(isUefi);
        if (!isUefi) {
            inputKernel.setTextAndMoveCursor(kernel);
            inputInitrd.setTextAndMoveCursor(initrd);
            inputCmdline.setTextAndMoveCursor(cmdline);
        }
        swAutoUp.setChecked(item.optBoolean("auto_up", false));
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        if (!swUefi.isChecked()) {
            var kernel = inputKernel.getText();
            var initrd = inputInitrd.getText();
            inputKernel.setError(null);
            inputInitrd.setError(null);
            if (!checkFilePath(kernel, true)) {
                inputKernel.setError(parent.getString(R.string.create_vm_error_path_invalid));
                return false;
            }
            if (!initrd.isEmpty() && !checkFilePath(initrd, true)) {
                inputInitrd.setError(parent.getString(R.string.create_vm_error_path_invalid));
                return false;
            }
        }
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        var item = config.item;
        if (swUefi.isChecked()) {
            item.set("kernel", PATH_EDK2_FIRMWARE);
            item.set("initrd", "");
            item.set("cmdline", "");
        } else {
            item.set("kernel", inputKernel.getText());
            item.set("initrd", inputInitrd.getText());
            item.set("cmdline", inputCmdline.getText());
        }
        item.set("auto_up", swAutoUp.isChecked());
    }

    private void showKernelBrowseDialog() {
        MenuItem.OnMenuItemClickListener listener = item -> {
            onKernelBrowseClick(item);
            return true;
        };
        MenuDialogBuilder.showSimple(
            parent,
            R.string.edit_vm_kernel_browse_title,
            R.menu.menu_vm_kernel_browse,
            listener
        );
    }

    private void showInitrdBrowseDialog() {
        MenuItem.OnMenuItemClickListener listener = item -> {
            onInitrdBrowseClick(item);
            return true;
        };
        MenuDialogBuilder.showSimple(
            parent,
            R.string.edit_vm_initrd_browse_title,
            R.menu.menu_vm_initrd_browse,
            listener
        );
    }

    private void onKernelBrowseClick(@NonNull MenuItem item) {
        var id = item.getItemId();
        if (id == R.id.menu_kernel_clear) {
            inputKernel.setText("");
            return;
        }
        if (id == R.id.menu_kernel_external) {
            parent.currentPicker = uri -> {
                if (uri != null)
                    inputKernel.setTextAndMoveCursor(resolveUriPath(parent, uri));
                parent.currentPicker = null;
            };
            parent.filePickerLauncher.launch(new String[]{"*/*"});
            return;
        }
        String path;
        if (id == R.id.menu_kernel_builtin) {
            path = PATH_BUILTIN_KERNEL;
        } else if (id == R.id.menu_kernel_microdroid) {
            path = PATH_MICRODROID_KERNEL;
        } else {
            return;
        }
        shellAsyncCheckExists(parent, path, exists -> {
            if (exists) {
                inputKernel.setTextAndMoveCursor(path);
            } else {
                new MaterialAlertDialogBuilder(parent)
                    .setTitle(R.string.edit_vm_kernel_browse_title)
                    .setMessage(parent.getString(R.string.edit_vm_kernel_not_found, path))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            }
        });
    }

    private void onInitrdBrowseClick(@NonNull MenuItem item) {
        var id = item.getItemId();
        if (id == R.id.menu_initrd_clear) {
            inputInitrd.setText("");
            return;
        }
        if (id == R.id.menu_initrd_external) {
            parent.currentPicker = uri -> {
                if (uri != null)
                    inputInitrd.setTextAndMoveCursor(resolveUriPath(parent, uri));
                parent.currentPicker = null;
            };
            parent.filePickerLauncher.launch(new String[]{"*/*"});
            return;
        }
        String path;
        if (id == R.id.menu_initrd_builtin) {
            path = PATH_BUILTIN_INITRD;
        } else if (id == R.id.menu_initrd_microdroid) {
            path = PATH_MICRODROID_INITRD;
        } else if (id == R.id.menu_initrd_microdroid_debug) {
            path = PATH_MICRODROID_INITRD_DEBUG;
        } else {
            return;
        }
        shellAsyncCheckExists(parent, path, exists -> {
            if (exists) {
                inputInitrd.setTextAndMoveCursor(path);
            } else {
                new MaterialAlertDialogBuilder(parent)
                    .setTitle(R.string.edit_vm_initrd_browse_title)
                    .setMessage(parent.getString(R.string.edit_vm_initrd_not_found, path))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            }
        });
    }
}
