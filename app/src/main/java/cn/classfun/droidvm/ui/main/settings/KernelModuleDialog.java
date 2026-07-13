package cn.classfun.droidvm.ui.main.settings;

import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import cn.classfun.droidvm.R;

/**
 * Lists the host kernel modules {@link KernelModuleManager} ships for this device's KMI and lets the
 * user load/unload each one and toggle auto-load at app start. The module scan and insmod/rmmod all
 * touch root, so they run on a background pool; the view is (re)built on the main thread.
 */
public final class KernelModuleDialog {
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout container;

    public KernelModuleDialog(@NonNull Context ctx) {
        this.ctx = ctx;
    }

    public void show() {
        var scroll = new ScrollView(ctx);
        container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        container.setPadding(pad, dp(8), pad, dp(8));
        scroll.addView(container);

        var loading = new TextView(ctx);
        loading.setText(R.string.kernel_module_loading);
        container.addView(loading);

        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.kernel_module_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show();

        refresh();
    }

    private void refresh() {
        runOnPool(() -> {
            var mods = KernelModuleManager.list();
            main.post(() -> render(mods));
        });
    }

    private void render(@NonNull List<KernelModuleManager.Module> mods) {
        if (container == null) return;
        container.removeAllViews();
        if (mods.isEmpty()) {
            var tv = new TextView(ctx);
            tv.setText(R.string.kernel_module_none);
            container.addView(tv);
            return;
        }
        for (var mod : mods) container.addView(buildRow(mod));
    }

    @NonNull
    private View buildRow(@NonNull KernelModuleManager.Module mod) {
        var row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        var name = new TextView(ctx);
        name.setText(mod.name);
        name.setTextSize(16);
        row.addView(name);

        var status = new TextView(ctx);
        status.setText(mod.loaded ? R.string.kernel_module_loaded : R.string.kernel_module_unloaded);
        row.addView(status);

        var controls = new LinearLayout(ctx);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(6), 0, 0);

        var toggle = new MaterialButton(ctx);
        toggle.setText(mod.loaded ? R.string.kernel_module_unload : R.string.kernel_module_load);
        toggle.setOnClickListener(v -> doToggle(mod));
        controls.addView(toggle);

        var spacer = new Space(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(spacer);

        var autoLabel = new TextView(ctx);
        autoLabel.setText(R.string.kernel_module_autostart);
        autoLabel.setPadding(0, 0, dp(8), 0);
        controls.addView(autoLabel);

        var sw = new SwitchCompat(ctx);
        sw.setChecked(KernelModuleManager.isAutostart(ctx, mod.name));
        sw.setOnCheckedChangeListener((b, checked) ->
            KernelModuleManager.setAutostart(ctx, mod.name, checked));
        controls.addView(sw);

        row.addView(controls);
        return row;
    }

    private void doToggle(@NonNull KernelModuleManager.Module mod) {
        Toast.makeText(ctx, R.string.kernel_module_working, Toast.LENGTH_SHORT).show();
        runOnPool(() -> {
            boolean ok = mod.loaded
                ? KernelModuleManager.unload(mod.name)
                : KernelModuleManager.load(mod.path);
            main.post(() -> {
                Toast.makeText(ctx, ok ? R.string.kernel_module_ok : R.string.kernel_module_fail,
                    Toast.LENGTH_SHORT).show();
                refresh();
            });
        });
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
