// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.settings;

import android.content.Context;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;

/**
 * The settings entry to the kernel module list: shows {@link KernelModuleListController}'s view
 * in a dialog. The first-run setup wizard shows the same list as a step instead.
 */
public final class KernelModuleDialog {
    private final Context ctx;

    public KernelModuleDialog(@NonNull Context ctx) {
        this.ctx = ctx;
    }

    public void show() {
        var content = LayoutInflater.from(ctx).inflate(R.layout.dialog_kernel_modules, null);

        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.kernel_module_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .show();

        new KernelModuleListController(ctx, content).refresh();
    }
}
