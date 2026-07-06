package cn.classfun.droidvm.ui.vm.boot;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

/**
 * GRUB-style boot entry menu shown on a manual GUI start of a VM that
 * boots from a disk image: the resolved entry is preselected, a countdown
 * runs on the Boot button (touching the list stops it, like pressing a
 * key in GRUB), and the choice is one-shot unless "remember" is ticked.
 */
public final class BootMenuDialog {
    /** RadioButton id of the fixed "DroidVM built-in kernel" entry, kept
     *  well clear of the per-index ids ({@link #idFor}). */
    private static final int BUILTIN_RADIO_ID = 1_000_000;

    /** Extra grace on the auto-boot countdown when the entry it would boot
     *  carries a warning, giving the user time to pick another. */
    private static final int WARNED_COUNTDOWN_GRACE = 3;

    public interface OnProceed {
        /**
         * @param bootEntry      one-shot entry selection key for vm_start
         *                       (null = follow stored config;
         *                       {@link BootConfig#BUILTIN_ENTRY_KEY} = built-in kernel)
         * @param remember       whether to persist the selection (auto persists
         *                       as "clear pin"; built-in switches to a manual config)
         * @param selected       image entry to pin when remembering (null otherwise)
         * @param builtinCmdline the cmdline the built-in kernel would use, for
         *                       persisting; non-null only for the built-in entry
         */
        void proceed(@Nullable String bootEntry, boolean remember,
                     @Nullable BootConfig.ImageEntry selected,
                     @Nullable String builtinCmdline);
    }

    private BootMenuDialog() {
    }

    /** True when this VM's config wants the menu on manual start. */
    public static boolean wanted(@NonNull VMConfig config) {
        var boot = BootConfig.of(config);
        return boot.getProtocol() == BootConfig.Protocol.LINUX
            && boot.getLinuxSource() == BootConfig.LinuxSource.IMAGE
            && boot.getBootWait() > 0;
    }

    public static void show(
        @NonNull Context context,
        @NonNull VMConfig config,
        @NonNull OnProceed onProceed,
        @NonNull Runnable onCancel
    ) {
        var boot = BootConfig.of(config);
        var view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_boot_menu, null);
        RadioGroup group = view.findViewById(R.id.rg_boot_entries);
        TextView summary = view.findViewById(R.id.tv_boot_menu_summary);
        TextView warn = view.findViewById(R.id.tv_boot_menu_warn);
        CheckBox remember = view.findViewById(R.id.cb_boot_menu_remember);

        var state = new Object() {
            @Nullable
            BootEntries entries;
            long secondsLeft = Math.max(boot.getBootWait(), 1);
            boolean countdownActive = false;
            boolean done = false;
        };
        var handler = new Handler(Looper.getMainLooper());

        var dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(
                R.string.edit_vm_boot_menu_title, config.getName()))
            .setView(view)
            .setPositiveButton(R.string.edit_vm_boot_menu_boot, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .create();

        Runnable fire = () -> {
            if (state.done) return;
            state.done = true;
            dialog.dismiss();
            var entries = state.entries;
            int checkedId = group.getCheckedRadioButtonId();
            if (checkedId == BUILTIN_RADIO_ID) {
                onProceed.proceed(BootConfig.BUILTIN_ENTRY_KEY, remember.isChecked(),
                    null, builtinCmdline(boot, entries));
                return;
            }
            // radio index 0 = auto; entry i sits at radio index i + 1
            int index = checkedId - 1;
            if (entries == null || index <= 0) {
                onProceed.proceed(null, remember.isChecked(), null, null);
                return;
            }
            var entry = entries.entries.get(index - 1);
            onProceed.proceed(
                entry.selectionKey(), remember.isChecked(), entry.toImageEntry(), null);
        };

        var tick = new Runnable() {
            @Override
            public void run() {
                if (state.done || !state.countdownActive) return;
                var btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (state.secondsLeft <= 0) {
                    fire.run();
                    return;
                }
                btn.setText(context.getString(
                    R.string.edit_vm_boot_menu_boot_in, state.secondsLeft));
                state.secondsLeft--;
                handler.postDelayed(this, 1000);
            }
        };

        Runnable stopCountdown = () -> {
            if (!state.countdownActive) return;
            state.countdownActive = false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setText(R.string.edit_vm_boot_menu_boot);
        };

        // fixed escape hatch pinned to the bottom of the list: boot the disk
        // with DroidVM's built-in kernel (useful when the guest's own kernel
        // won't direct-boot, or its bootloader can't be scanned)
        Runnable addBuiltin = () -> addRadio(context, group, BUILTIN_RADIO_ID,
            context.getString(R.string.edit_vm_boot_entry_builtin), null, stopCountdown);

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> fire.run());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> {
                    state.done = true;
                    dialog.dismiss();
                    onCancel.run();
                });
        });
        dialog.show();

        // auto option is always present; entries arrive after the scan, and
        // the built-in entry is appended last so it stays at the bottom
        addRadio(context, group, idFor(0),
            context.getString(R.string.edit_vm_boot_entry_auto), null, stopCountdown);
        group.check(idFor(0));
        summary.setVisibility(VISIBLE);
        summary.setText(R.string.edit_vm_boot_detect_scanning);

        var image = firstImagePath(config, boot.getImageDisk());
        if (image == null) {
            summary.setText(R.string.edit_vm_boot_detect_no_disk);
            addBuiltin.run();
            return;
        }
        BootEntries.scan(image, (result, error) -> handler.post(() -> {
            if (state.done) return;
            if (result == null) {
                summary.setText(context.getString(
                    R.string.edit_vm_boot_detect_failed, error));
                addBuiltin.run();
                return;
            }
            state.entries = result;
            var pinned = boot.getImageEntry();
            var resolved = result.resolve(pinned);
            boolean protectedVm = isProtectedVm(config);
            for (int i = 0; i < result.entries.size(); i++) {
                var entry = result.entries.get(i);
                addRadio(context, group, idFor(i + 1), entry.displayLabel(context),
                    entryWarning(context, protectedVm, entry), stopCountdown);
            }
            addBuiltin.run();
            // the entry "Auto" would boot is whatever the stored config
            // resolves to now; surface its warning on the Auto line too
            var autoWarn = resolved != null
                ? entryWarning(context, protectedVm, resolved) : null;
            var autoRadio = (RadioButton) group.findViewById(idFor(0));
            if (autoRadio != null)
                autoRadio.setText(warnedLabel(autoRadio,
                    context.getString(R.string.edit_vm_boot_entry_auto), autoWarn));
            if (resolved != null && pinned != null && !result.isFallback(pinned))
                group.check(idFor(result.entries.indexOf(resolved) + 1));
            else
                group.check(idFor(0));
            if (result.isFallback(pinned)) {
                warn.setVisibility(VISIBLE);
                warn.setText(context.getString(
                    R.string.edit_vm_boot_menu_fallback,
                    pinned.title != null ? pinned.title : pinned.id));
            }
            updateSummary(context, summary, boot, result, group);
            group.setOnCheckedChangeListener((g, id) ->
                updateSummary(context, summary, boot, result, group));
            // countdown only starts on a successful scan; a warned auto-boot
            // target buys the user a few extra seconds to react
            if (autoWarn != null) state.secondsLeft += WARNED_COUNTDOWN_GRACE;
            state.countdownActive = true;
            tick.run();
        }));
    }

    private static void updateSummary(
        @NonNull Context context,
        @NonNull TextView summary,
        @NonNull BootConfig boot,
        @NonNull BootEntries entries,
        @NonNull RadioGroup group
    ) {
        int checkedId = group.getCheckedRadioButtonId();
        if (checkedId == BUILTIN_RADIO_ID) {
            var line = context.getString(R.string.edit_vm_boot_entry_builtin);
            var cmdline = builtinCmdline(boot, entries);
            if (!cmdline.isEmpty()) line += "\n" + cmdline;
            summary.setVisibility(VISIBLE);
            summary.setText(line);
            return;
        }
        int index = checkedId - 1;
        var entry = index <= 0
            ? entries.resolve(null)
            : entries.entries.get(index - 1);
        if (entry == null) {
            summary.setVisibility(GONE);
            return;
        }
        var line = fmt("%s | %s", entry.kernel, entry.source);
        var cmdline = entry.effectiveCmdline(boot.isVdafix());
        if (!cmdline.isEmpty()) line += "\n" + cmdline;
        summary.setVisibility(VISIBLE);
        summary.setText(line);
    }

    /**
     * The cmdline the built-in kernel would boot with, mirroring
     * {@code BootPlan.builtinCmdline}: the image cmdline override if set,
     * else the resolved entry's effective (vdafix-adjusted) cmdline so the
     * right {@code root=} comes up, else the manual default.
     */
    @NonNull
    private static String builtinCmdline(
        @NonNull BootConfig boot, @Nullable BootEntries entries) {
        var override = boot.getImageCmdline();
        if (!override.isEmpty()) return override;
        if (entries != null) {
            var entry = entries.resolve(boot.getImageEntry());
            if (entry != null) {
                var cmdline = entry.effectiveCmdline(boot.isVdafix());
                if (!cmdline.isEmpty()) return cmdline;
            }
        }
        return BootConfig.DEFAULT_MANUAL_CMDLINE;
    }

    private static int idFor(int index) {
        // RadioButton ids must be positive; 0 is reserved/invalid
        return index + 1;
    }

    private static void addRadio(
        @NonNull Context context,
        @NonNull RadioGroup group,
        int id,
        @NonNull String label,
        @Nullable String warning,
        @NonNull Runnable onTouched
    ) {
        var rb = new RadioButton(context);
        rb.setId(id);
        rb.setText(warnedLabel(rb, label, warning));
        rb.setOnClickListener(v -> onTouched.run());
        group.addView(rb);
    }

    /**
     * The radio label, with {@code warning} appended as a red second line
     * when present (the per-entry warning area). Plain {@code label}
     * otherwise.
     */
    @NonNull
    private static CharSequence warnedLabel(
        @NonNull TextView colorSource,
        @NonNull String label,
        @Nullable String warning
    ) {
        if (warning == null || warning.isEmpty()) return label;
        var sb = new SpannableStringBuilder(label).append('\n').append(warning);
        int color = MaterialColors.getColor(
            colorSource, androidx.appcompat.R.attr.colorError);
        sb.setSpan(new ForegroundColorSpan(color),
            label.length() + 1, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * Whether this VM boots as a gunyah protected VM (protected, or
     * protected-without-firmware) -- read from the stored config, where a
     * guest kernel without CONFIG_DMA_RESTRICTED_POOL cannot drive virtio.
     * Mirrors {@code VMEditBootTab.isProtectedVm}.
     */
    private static boolean isProtectedVm(@NonNull VMConfig config) {
        var pvm = optEnum(config.item, "protected_vm",
            ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        return pvm == ProtectedVM.PROTECTED_PROTECTED
            || pvm == ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;
    }

    /**
     * Optional warning for a boot entry, or null when it has none. Today the
     * only case is a kernel lbx flagged as lacking CONFIG_DMA_RESTRICTED_POOL
     * on a protected VM (same condition as the edit tab's detection card),
     * shown here as a terse one-liner since the menu is cramped -- the edit
     * tab keeps the full explanation.
     */
    @Nullable
    private static String entryWarning(
        @NonNull Context context,
        boolean protectedVm,
        @NonNull BootEntries.Entry entry
    ) {
        if (protectedVm && entry.lacksRestrictedDmaPool())
            return context.getString(R.string.edit_vm_boot_entry_dma_warn);
        return null;
    }

    @Nullable
    private static String firstImagePath(@NonNull VMConfig config, int preferred) {
        var disks = config.item.opt("disks", null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY))
            return null;
        var arr = disks.asArray();
        if (preferred >= 0 && preferred < arr.size()) {
            var p = arr.get(preferred).optString("path", "");
            if (!p.isEmpty()) return p;
        }
        for (var d : arr) {
            var p = d.optString("path", "");
            if (!p.isEmpty()) return p;
        }
        return null;
    }
}
