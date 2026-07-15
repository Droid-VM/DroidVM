package cn.classfun.droidvm.ui.main.settings;

import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

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
    private final LayoutInflater inflater;
    private TextView kmi, state;
    private LinearLayout list;

    public KernelModuleDialog(@NonNull Context ctx) {
        this.ctx = ctx;
        this.inflater = LayoutInflater.from(ctx);
    }

    public void show() {
        var content = inflater.inflate(R.layout.dialog_kernel_modules, null);
        kmi = content.findViewById(R.id.km_kmi);
        state = content.findViewById(R.id.km_state);
        list = content.findViewById(R.id.km_list);

        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.kernel_module_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .show();

        refresh();
    }

    private void refresh() {
        runOnPool(() -> {
            var kmiName = KernelModuleManager.deviceKmi();
            var mods = KernelModuleManager.list();
            main.post(() -> render(kmiName, mods));
        });
    }

    private void render(@Nullable String kmiName, @NonNull List<KernelModuleManager.Module> mods) {
        if (list == null) return;
        if (kmiName != null) {
            kmi.setText(ctx.getString(R.string.kernel_module_kmi, kmiName));
            kmi.setVisibility(View.VISIBLE);
        }
        list.removeAllViews();
        if (mods.isEmpty()) {
            state.setText(R.string.kernel_module_none);
            state.setVisibility(View.VISIBLE);
            return;
        }
        state.setVisibility(View.GONE);
        for (var mod : mods) list.addView(buildCard(mod));
    }

    @NonNull
    private View buildCard(@NonNull KernelModuleManager.Module mod) {
        var card = inflater.inflate(R.layout.item_kernel_module, list, false);
        TextView name = card.findViewById(R.id.km_name);
        TextView status = card.findViewById(R.id.km_status);
        ImageView dot = card.findViewById(R.id.km_dot);
        Button toggle = card.findViewById(R.id.km_toggle);
        MaterialSwitch autostart = card.findViewById(R.id.km_autostart);

        name.setText(mod.name);
        status.setText(mod.loaded ? R.string.kernel_module_loaded : R.string.kernel_module_unloaded);
        int tint = MaterialColors.getColor(card, mod.loaded
            ? androidx.appcompat.R.attr.colorPrimary
            : com.google.android.material.R.attr.colorOutline);
        status.setTextColor(tint);
        dot.setImageTintList(ColorStateList.valueOf(tint));

        toggle.setText(mod.loaded ? R.string.kernel_module_unload : R.string.kernel_module_load);
        toggle.setOnClickListener(v -> {
            v.setEnabled(false);
            doToggle(mod);
        });

        autostart.setChecked(KernelModuleManager.isAutostart(ctx, mod.name));
        autostart.setOnCheckedChangeListener((b, checked) ->
            KernelModuleManager.setAutostart(ctx, mod.name, checked));

        return card;
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
}
