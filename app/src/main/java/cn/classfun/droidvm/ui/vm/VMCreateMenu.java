// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.ui.disk.lxc.CreateLinuxVmActivity;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.pkg.imports.VMPkgImportActivity;

/**
 * The "create a VM" chooser - Linux from a distro image, Windows (pointer to the image builder),
 * import a package, or the full editor. One entry for the VM list's + button and the home
 * screen's wizard card, so both offer exactly the same paths.
 */
public final class VMCreateMenu {
    private VMCreateMenu() {
    }

    public static void show(@NonNull Context context) {
        MenuDialogBuilder.showSimple(
            context,
            R.string.vm_create_mode_title,
            R.menu.menu_vm_create,
            item -> {
                var id = item.getItemId();
                if (id == R.id.menu_vm_create_linux) {
                    context.startActivity(new Intent(context, CreateLinuxVmActivity.class));
                } else if (id == R.id.menu_vm_create_windows) {
                    showWindowsVmUnavailableDialog(context);
                } else if (id == R.id.menu_vm_create_import) {
                    context.startActivity(new Intent(context, VMPkgImportActivity.class));
                } else if (id == R.id.menu_vm_create_customize) {
                    context.startActivity(new Intent(context, VMEditActivity.class));
                } else {
                    return false;
                }
                return true;
            }
        );
    }

    private static void showWindowsVmUnavailableDialog(@NonNull Context context) {
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.windows_vm_unavailable_title)
            .setMessage(R.string.windows_vm_unavailable_message)
            .setPositiveButton(R.string.windows_vm_open_script, (dialog, which) ->
                context.startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Droid-VM/win11-arm64-image-builder/blob/master/windows_build.ps1")
                )))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
