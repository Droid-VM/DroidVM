// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.operation;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.disk.create.DiskCompress;
import cn.classfun.droidvm.ui.main.settings.MainSettingsFragment;

/**
 * Resolves the compression a disk optimize should target: the preferred-compression setting
 * when it names one, otherwise (the "ask every time" default) a prompt whose "remember"
 * checkbox writes the choice back to the setting. Only compressions crosvm can boot from
 * ({@link DiskCompress#CROSVM_SUPPORTED}) are offered - the choices grow automatically as
 * that set does. Call on the main thread with a UI context.
 */
public final class OptimizeCompression {
    private OptimizeCompression() {
    }

    /** Display label: the shared enum label, except NONE which reads "uncompressed" here. */
    @StringRes
    public static int labelOf(@NonNull DiskCompress compress) {
        return compress == DiskCompress.NONE
            ? R.string.disk_compress_none : compress.getStringId();
    }

    public static void resolve(
        @NonNull Context context,
        @NonNull Runnable onCancel,
        @NonNull Consumer<DiskCompress> onChosen
    ) {
        var preferred = DiskCompress.fromValue(
            MainSettingsFragment.getOptimizeCompression(context));
        if (preferred != null && preferred.isCrosvmSupported()) {
            onChosen.accept(preferred);
            return;
        }
        var view = LayoutInflater.from(context).inflate(
            R.layout.dialog_optimize_compress, null);
        RadioGroup group = view.findViewById(R.id.compress_group);
        MaterialCheckBox remember = view.findViewById(R.id.compress_remember);
        for (var compress : DiskCompress.CROSVM_SUPPORTED) {
            var radio = new MaterialRadioButton(context);
            radio.setId(View.generateViewId());
            radio.setTag(compress);
            radio.setText(labelOf(compress));
            group.addView(radio);
        }
        // First (and today only) option pre-selected.
        if (group.getChildCount() > 0)
            group.check(group.getChildAt(0).getId());
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_optimize_compression_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, (d, w) -> onCancel.run())
            .setOnCancelListener(d -> onCancel.run())
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                var checked = group.findViewById(group.getCheckedRadioButtonId());
                var chosen = checked == null
                    ? DiskCompress.NONE : (DiskCompress) checked.getTag();
                if (remember.isChecked())
                    MainSettingsFragment.setOptimizeCompression(context, chosen.value());
                onChosen.accept(chosen);
            })
            .show();
    }
}
