// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.widgets.tools;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.joinNonEmpty;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.utils.CpuUtils;

/**
 * Multi-choice host CPU core picker: one checkbox per core labelled with its
 * max frequency and little/big/prime tier, plus a neutral button that re-checks
 * just the big cores. Shared by the qemu-img affinity setting and the per-vCPU
 * / GPU-cpuset pickers in the VM editor, which all need the same list.
 *
 * <p>Selections are exchanged as crosvm CPUSET strings ({@code "0,1-3,5"}) so
 * callers can hand the value straight to a flag or store it as-is.
 */
public final class CpuCorePickerDialog {
    private CpuCorePickerDialog() {
    }

    /**
     * @param selected cores to check initially, as a CPUSET string
     * @param onPicked receives the new selection as a CPUSET string; may be empty
     */
    public static void show(
        @NonNull Context context,
        @StringRes int titleRes,
        @NonNull String selected,
        @NonNull Consumer<String> onPicked
    ) {
        show(context, context.getString(titleRes), selected, onPicked);
    }

    public static void show(
        @NonNull Context context,
        @NonNull CharSequence title,
        @NonNull String selected,
        @NonNull Consumer<String> onPicked
    ) {
        var cores = CpuUtils.getCores();
        int tiers = CpuUtils.tierCount(cores);
        var labels = new String[cores.size()];
        for (int i = 0; i < cores.size(); i++)
            labels[i] = label(context, cores.get(i), tiers);

        var selectedIdx = toSet(selected);
        var checked = new boolean[cores.size()];
        for (int i = 0; i < cores.size(); i++)
            checked[i] = selectedIdx.contains(cores.get(i).index);

        var dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                checked[which] = isChecked)
            .setNeutralButton(R.string.settings_cpu_affinity_big_only, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                var sb = new StringBuilder();
                for (int i = 0; i < cores.size(); i++) {
                    if (!checked[i]) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(cores.get(i).index);
                }
                onPicked.accept(sb.toString());
            })
            .create();
        dialog.show();
        // Re-check only the big cores without dismissing the dialog.
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            var bigIdx = toSet(CpuUtils.defaultBigCoresCsv());
            var list = dialog.getListView();
            for (int i = 0; i < cores.size(); i++) {
                checked[i] = bigIdx.contains(cores.get(i).index);
                list.setItemChecked(i, checked[i]);
            }
        });
    }

    /**
     * Gap between the columns of a core label. Callers that describe a core
     * outside this list use it too, so the wording lines up wherever it appears.
     */
    public static final String LABEL_SEP = "    ";

    /**
     * Localised tier name ("little"/"big"/"prime") for a core, or empty when the
     * host has a single tier and the distinction says nothing.
     */
    @NonNull
    public static String tierLabel(
        @NonNull Context context, @NonNull CpuUtils.CpuCore core, int tiers) {
        int tierRes;
        if (tiers <= 1) tierRes = 0;
        else if (core.tier == 0) tierRes = R.string.settings_cpu_affinity_tier_little;
        else if (tiers >= 3 && core.tier == tiers - 1)
            tierRes = R.string.settings_cpu_affinity_tier_prime;
        else tierRes = R.string.settings_cpu_affinity_tier_big;
        return tierRes == 0 ? "" : context.getString(tierRes);
    }

    /** {@code "CPU4    2.80 GHz    Big"} -- frequency and tier omitted when unknown. */
    @NonNull
    public static String label(
        @NonNull Context context, @NonNull CpuUtils.CpuCore core, int tiers) {
        return joinNonEmpty(LABEL_SEP,
            fmt("CPU%d", core.index),
            CpuUtils.formatFreq(core.maxFreqKHz),
            tierLabel(context, core, tiers));
    }

    @NonNull
    public static Set<Integer> toSet(@Nullable String cpuSet) {
        return cpuSet == null
            ? new HashSet<>() : new HashSet<>(CpuUtils.parseCpuSet(cpuSet));
    }

    /** Host core indices present on this device. */
    @NonNull
    public static Set<Integer> hostCoreIndices(@NonNull List<CpuUtils.CpuCore> cores) {
        var out = new HashSet<Integer>();
        for (var core : cores) out.add(core.index);
        return out;
    }
}
