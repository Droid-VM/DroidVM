// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.settings;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
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
import java.util.function.Supplier;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.hugepage.HugePageActivity;

/**
 * Renders the host kernel module list into any view carrying {@code km_kmi}/{@code km_state}/
 * {@code km_list} (the {@code dialog_kernel_modules} layout) -- the settings dialog and the
 * first-run setup step both show the same list through this. Each shipped module
 * ({@link KernelModuleManager}) gets a card with load/unload and an auto-load-at-app-start
 * switch; a GH-Hugepage-Reserve entry is always pinned first: that module is a separately
 * installed Magisk/KernelSU package (it must load at early boot), so its card offers
 * Download/Manage instead. Every card has a "why is this needed" button showing the
 * plain-language description from {@link KernelModuleDescriptions}. The module scan and
 * insmod/rmmod all touch root, so they run on a background pool; the view is (re)built on the
 * main thread.
 */
public final class KernelModuleListController {
    private static final String HUGEPAGE_PROP = "/data/adb/modules/gh-hugepage-reserve/module.prop";
    private static final String HUGEPAGE_SYSFS = "/sys/module/gh_hugepage_reserve";
    private static final String HUGEPAGE_RELEASES =
        "https://github.com/Droid-VM/gh-hugepage-reserve/releases";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LayoutInflater inflater;
    private final TextView kmi, state;
    private final LinearLayout list;

    public KernelModuleListController(@NonNull Context ctx, @NonNull View content) {
        this.ctx = ctx;
        this.inflater = LayoutInflater.from(ctx);
        kmi = content.findViewById(R.id.km_kmi);
        state = content.findViewById(R.id.km_state);
        list = content.findViewById(R.id.km_list);
    }

    public void refresh() {
        runOnPool(() -> {
            // Explicitly, not via list(): with no modules directory list() returns before it
            // would have loaded the rules, and the hugepage card still needs them.
            KernelModuleMatch.preload(ctx);
            var kmiName = KernelModuleManager.deviceKmi();
            var mods = KernelModuleManager.list(ctx);
            KernelModuleDescriptions.preload(ctx);
            // GH-Hugepage-Reserve is matched like any other module; on a device it does not
            // apply to, the card is absent rather than offering a download that cannot help.
            boolean hpApplies = KernelModuleMatch.allowsHugepage();
            // Failing to see /data/adb (no root, no Magisk) reads as false -> Download,
            // which is the wanted "can't tell whether it's installed" answer.
            boolean hpInstalled = hpApplies && shellCheckExists(HUGEPAGE_PROP);
            boolean hpLoaded = hpApplies && shellCheckExists(HUGEPAGE_SYSFS);
            main.post(() -> render(kmiName, mods, hpApplies, hpInstalled, hpLoaded));
        });
    }

    private void render(@Nullable String kmiName, @NonNull List<KernelModuleManager.Module> mods,
                        boolean hpApplies, boolean hpInstalled, boolean hpLoaded) {
        if (list == null) return;
        if (kmiName != null) {
            kmi.setText(ctx.getString(R.string.kernel_module_kmi, kmiName));
            kmi.setVisibility(View.VISIBLE);
        }
        list.removeAllViews();
        if (hpApplies) list.addView(buildHugepageCard(hpInstalled, hpLoaded));
        for (var mod : mods) list.addView(buildCard(mod));
        // "None" means nothing at all applies to this device (a non-Qualcomm phone, say), not
        // merely that the .ko list under an applicable hugepage card happens to be empty.
        boolean empty = mods.isEmpty() && !hpApplies;
        state.setText(R.string.kernel_module_none);
        state.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    @NonNull
    private View buildCard(@NonNull KernelModuleManager.Module mod) {
        var card = inflater.inflate(R.layout.item_kernel_module, list, false);
        TextView name = card.findViewById(R.id.km_name);
        Button toggle = card.findViewById(R.id.km_toggle);
        MaterialSwitch autostart = card.findViewById(R.id.km_autostart);

        name.setText(mod.name);
        bindStatus(card, mod.loaded);
        bindWhy(card, mod.name, KernelModuleDescriptions.hasModulePage(mod.name),
            () -> KernelModuleDescriptions.modulePage(ctx, mod.name));

        toggle.setText(mod.loaded ? R.string.kernel_module_unload : R.string.kernel_module_load);
        toggle.setOnClickListener(v -> {
            v.setEnabled(false);
            doToggle(mod);
        });

        // Arming autostart requires the module to have been seen loaded. The switch stays
        // pressable so the refusal can explain itself -- a greyed-out control just looks broken.
        // Rejected flips it back to off, and that second callback is a no-op (disarming is
        // always allowed), so there is no loop.
        autostart.setChecked(KernelModuleManager.isAutostart(ctx, mod.name)
            && KernelModuleManager.isVerified(mod.name));
        autostart.setOnCheckedChangeListener((b, checked) -> {
            if (!KernelModuleManager.setAutostart(ctx, mod.name, checked)) {
                b.setChecked(false);
                Toast.makeText(ctx, R.string.kernel_module_autostart_needs_load,
                    Toast.LENGTH_SHORT).show();
            }
        });

        return card;
    }

    /**
     * GH-Hugepage-Reserve is not one of the shipped .ko files: it installs as a Magisk/KernelSU
     * module so it can load at early boot, before memory fragments. Its card therefore has no
     * load/unload or autostart -- just Download (releases page) or, once installed, Manage
     * (the dedicated management screen, which owns enable/pool-size/attribution).
     */
    @NonNull
    private View buildHugepageCard(boolean installed, boolean loaded) {
        var card = inflater.inflate(R.layout.item_kernel_module, list, false);
        TextView name = card.findViewById(R.id.km_name);
        Button action = card.findViewById(R.id.km_toggle);

        name.setText(R.string.kernel_module_hugepage_name);
        bindStatus(card, loaded);
        bindWhy(card, ctx.getString(R.string.kernel_module_hugepage_name),
            KernelModuleDescriptions.hasHugepagePage(),
            () -> KernelModuleDescriptions.hugepagePage(ctx));

        action.setText(installed ? R.string.kernel_module_manage : R.string.hugepage_download);
        action.setOnClickListener(installed
            ? v -> ctx.startActivity(new Intent(ctx, HugePageActivity.class))
            : v -> ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(HUGEPAGE_RELEASES))));

        card.findViewById(R.id.km_auto_row).setVisibility(View.GONE);
        return card;
    }

    /** Status line: loaded = theme primary, not loaded = bold in the theme's error red. */
    private void bindStatus(@NonNull View card, boolean loaded) {
        TextView status = card.findViewById(R.id.km_status);
        ImageView dot = card.findViewById(R.id.km_dot);
        status.setText(loaded ? R.string.kernel_module_loaded : R.string.kernel_module_unloaded);
        status.setTypeface(null, loaded ? Typeface.NORMAL : Typeface.BOLD);
        int tint = MaterialColors.getColor(card, loaded
            ? androidx.appcompat.R.attr.colorPrimary
            : androidx.appcompat.R.attr.colorError);
        status.setTextColor(tint);
        dot.setImageTintList(ColorStateList.valueOf(tint));
    }

    /**
     * "Why is this needed" opens the module's description page; with no page shipped for this
     * module (an older prebuilt, or one not extracted yet) the button hides instead of opening
     * an empty dialog. The page itself is only assembled on click -- {@code hasPage} is the
     * cheap check the card can afford while building the list.
     */
    private void bindWhy(@NonNull View card, @NonNull String title, boolean hasPage,
                         @NonNull Supplier<String> page) {
        Button why = card.findViewById(R.id.km_why);
        if (!hasPage) {
            why.setVisibility(View.GONE);
            return;
        }
        why.setOnClickListener(v -> {
            var html = page.get();
            if (html != null) KernelModuleWhyDialog.show(ctx, title, html);
        });
    }

    private void doToggle(@NonNull KernelModuleManager.Module mod) {
        Toast.makeText(ctx, R.string.kernel_module_working, Toast.LENGTH_SHORT).show();
        runOnPool(() -> {
            boolean ok = mod.loaded
                ? KernelModuleManager.unload(mod.name)
                : KernelModuleManager.loadAndVerify(mod);
            main.post(() -> {
                Toast.makeText(ctx, ok ? R.string.kernel_module_ok : R.string.kernel_module_fail,
                    Toast.LENGTH_SHORT).show();
                refresh();
            });
        });
    }
}
