// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.settings;

import android.app.Dialog;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;

/**
 * The settings entry to the kernel module list: shows {@link KernelModuleListController}'s view
 * in a dialog. The first-run setup wizard shows the same list as a step instead.
 *
 * <p>A {@link DialogFragment}, not a bare {@code AlertDialog.show()}: the fragment manager owns
 * it, so it is dismissed and reshown cleanly with the settings screen rather than leaking on a
 * back-stack change.
 *
 * <h3>Fitting the window to the orientation</h3>
 * The list is tall; a phone held sideways has little height, and M3's fixed 80dp top+bottom
 * background inset then leaves the scrolling area a single row. The activities here all set
 * {@code android:configChanges="orientation|screenSize|..."}, so a rotation does <b>not</b>
 * recreate the activity or rebuild this dialog -- it arrives as {@link #onConfigurationChanged}.
 * A build-time inset (set once in {@link #onCreateDialog}) would therefore keep its original
 * orientation's value forever. So the height is driven from {@link #applyWindowMetrics}, called
 * both when the dialog first shows and on every configuration change: landscape gives the window
 * almost the whole screen height (the list scrolls within it), portrait lets it wrap.
 */
public final class KernelModuleDialog extends DialogFragment {
    private static final String TAG = "kernel_modules";
    /** Landscape: fraction of screen height the dialog takes; the rest is a thin margin. */
    private static final float LANDSCAPE_HEIGHT_FRACTION = 0.94f;

    /** Show the dialog, or do nothing if it is already showing (e.g. a double tap). */
    public static void show(@NonNull FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) == null) new KernelModuleDialog().show(fm, TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var ctx = requireContext();
        var content = LayoutInflater.from(ctx).inflate(R.layout.dialog_kernel_modules, null);

        var dialog = new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.kernel_module_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .create();

        // The view is inflated; refresh can find its ids. Runs off the main thread and posts
        // back, so it is fine to kick off before the dialog is shown -- same as a fresh open.
        new KernelModuleListController(ctx, content).refresh();
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        applyWindowMetrics();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // A rotation lands here (configChanges keeps the activity alive), not in onCreateDialog.
        applyWindowMetrics();
    }

    /**
     * Size the dialog window to the current orientation. Only the height is touched -- the width
     * keeps whatever M3 chose (centred, its own horizontal insets). Landscape claims nearly the
     * full height so the module list is not squeezed to a row; portrait wraps its content as a
     * dialog normally would. Runs on show and on every configuration change, so a rotate with
     * the dialog open re-fits it instead of leaving a stale portrait/landscape size.
     */
    private void applyWindowMetrics() {
        var dialog = getDialog();
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        var res = getResources();
        boolean landscape =
            res.getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        var lp = window.getAttributes();
        lp.height = landscape
            ? Math.round(res.getDisplayMetrics().heightPixels * LANDSCAPE_HEIGHT_FRACTION)
            : WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(lp);
    }
}
