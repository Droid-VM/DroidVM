// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.operation;

import android.content.Context;
import android.content.ContextWrapper;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DiskDefaultsClient;
import cn.classfun.droidvm.ui.disk.create.DiskCompress;
import cn.classfun.droidvm.ui.main.settings.MainSettingsFragment;

import org.json.JSONObject;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

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
        var main = new Handler(Looper.getMainLooper());
        runOnPool(() -> {
            DiskDefaultsClient.Snapshot snapshot = null;
            String preference;
            try {
                snapshot = DiskDefaultsClient.getInstance().forNewOperation();
                preference = snapshot.optimizeCompression;
            } catch (DiskDefaultsClient.Blocked error) {
                if (!error.unsupported) { main.post(() -> unavailable(context, onCancel)); return; }
                preference = MainSettingsFragment.getOptimizeCompression(context);
            } catch (Exception error) { main.post(() -> unavailable(context, onCancel)); return; }
            final var baseline = snapshot;
            final var selected = preference;
            main.post(() -> {
                if (!alive(context)) { onCancel.run(); return; }
                resolveWithSnapshot(context, selected, baseline, onCancel, onChosen);
            });
        });
    }

    private static boolean alive(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                var activity = (Activity) current;
                return !activity.isFinishing() && !activity.isDestroyed();
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) break;
            current = next;
        }
        return true;
    }

    private static void unavailable(Context context, Runnable onCancel) {
        if (alive(context)) Toast.makeText(context, R.string.settings_disk_defaults_unavailable, Toast.LENGTH_LONG).show();
        onCancel.run();
    }

    private static void resolveWithSnapshot(Context context, String preference,
        DiskDefaultsClient.Snapshot baseline, Runnable onCancel, Consumer<DiskCompress> onChosen) {
        var preferred = DiskCompress.fromValue(preference);
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
                if (!remember.isChecked()) { onChosen.accept(chosen); return; }
                // Saving a shared default is its own CAS operation. A lost result must not
                // be presented as remembered or silently retried with a new identity.
                var main = new Handler(Looper.getMainLooper());
                runOnPool(() -> {
                    try {
                        var client = DiskDefaultsClient.getInstance();
                        if (baseline == null) {
                            if (client.hasPending() || client.supported()) throw new java.io.IOException("Legacy disk defaults no longer writable");
                            if (!context.getSharedPreferences("droidvm_prefs", Context.MODE_PRIVATE).edit()
                                .putString(DiskDefaultsClient.LEGACY_COMPRESSION, chosen.value()).commit())
                                throw new java.io.IOException("Could not save compression default");
                        } else {
                            var receipt = client.patch(baseline, new JSONObject().put(DiskDefaultsClient.COMPRESSION, chosen.value()));
                            if (!receipt.succeeded) throw new java.io.IOException("Compression default was not saved");
                            client.refresh();
                        }
                        main.post(() -> { if (alive(context)) onChosen.accept(chosen); else onCancel.run(); });
                    } catch (Exception error) { main.post(() -> unavailable(context, onCancel)); }
                });
            })
            .show();
    }
}
