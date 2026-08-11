// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.basic;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;
import static cn.classfun.droidvm.lib.utils.StringUtils.joinNonEmpty;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.CpuPlacementPlan;
import cn.classfun.droidvm.lib.utils.CpuUtils;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;
import cn.classfun.droidvm.ui.widgets.tools.CpuCorePickerDialog;

/**
 * Editor for where a VM's vCPUs run: one row per vCPU, each opening a host-core
 * picker, plus the derived-or-manual guest capacity/cluster values.
 *
 * <p>It hangs off the CPU count field's icon button rather than sitting inline in
 * the tab, because the row list is a function of that count: opening the dialog
 * reads the count once, so the rows can never disagree with it. (The inline
 * version had to rebuild on focus-loss, which a user could sidestep.)
 *
 * <p>Edits apply on OK only -- the working state is a copy, so Cancel is a real
 * cancel.
 */
public final class VMCpuAffinityDialog {
    /** Receives the accepted state; nothing is called on cancel. */
    public interface Callback {
        void onAccepted(
            @NonNull Map<Integer, List<Integer>> affinity,
            boolean auto,
            @NonNull String manualCapacity,
            @NonNull String manualClusters);
    }

    private final Context context;
    private final int vcpuCount;
    private final List<CpuUtils.CpuCore> hostCores;
    /** Distinct host frequency tiers; 1 means little/big says nothing here. */
    private final int hostTiers;
    /** Working copy; the caller's map is untouched until OK. */
    private final Map<Integer, List<Integer>> affinity;
    private final Callback callback;

    private final SwitchRowWidget swAffinity;
    private final View affinityOptions;
    private final LinearLayout rowsContainer;
    private final SwitchRowWidget swAuto;
    private final View autoPreview;
    private final View manualInputs;
    private final TextRowWidget rowCapacityPreview;
    private final TextRowWidget rowClusterPreview;
    private final TextInputEditText etCapacity;
    private final TextInputEditText etClusters;

    private int builtVcpuCount = -1;

    public VMCpuAffinityDialog(
        @NonNull Context context,
        int vcpuCount,
        @NonNull Map<Integer, List<Integer>> affinity,
        boolean auto,
        @NonNull String manualCapacity,
        @NonNull String manualClusters,
        @NonNull Callback callback
    ) {
        this.context = context;
        this.vcpuCount = Math.max(1, vcpuCount);
        this.hostCores = CpuUtils.getCores();
        this.hostTiers = CpuUtils.tierCount(this.hostCores);
        this.affinity = CpuPlacementPlan.orderedCopy(affinity);
        this.callback = callback;

        var view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_vm_cpu_affinity, null);
        swAffinity = view.findViewById(R.id.sw_cpu_affinity);
        affinityOptions = view.findViewById(R.id.cpu_affinity_options);
        rowsContainer = view.findViewById(R.id.vcpu_rows_container);
        swAuto = view.findViewById(R.id.sw_cpu_topology_auto);
        autoPreview = view.findViewById(R.id.cpu_topology_auto_preview);
        manualInputs = view.findViewById(R.id.cpu_topology_manual);
        rowCapacityPreview = view.findViewById(R.id.row_cpu_capacity_preview);
        rowClusterPreview = view.findViewById(R.id.row_cpu_cluster_preview);
        etCapacity = view.findViewById(R.id.et_cpu_capacity);
        etClusters = view.findViewById(R.id.et_cpu_clusters);

        // Drop bindings for vCPUs that no longer exist (count lowered since last edit).
        this.affinity.keySet().removeIf(vcpu -> vcpu >= this.vcpuCount);
        swAffinity.setChecked(!this.affinity.isEmpty());
        swAuto.setChecked(auto);
        etCapacity.setText(manualCapacity);
        etClusters.setText(manualClusters);

        swAffinity.setOnCheckedChangeListener(() -> {
            // First enable with nothing bound: identity (vCPU i -> host i) is the
            // mapping whose derived capacity/cluster mirror the host exactly.
            if (swAffinity.isChecked() && this.affinity.isEmpty()) prefillIdentity();
            updateVisibility();
        });
        swAuto.setOnCheckedChangeListener(() -> {
            // Going manual with empty fields: seed them with what auto produced, so
            // the user edits a working baseline instead of reconstructing it by hand.
            if (!swAuto.isChecked()) seedManualIfEmpty();
            updateVisibility();
        });
        updateVisibility();

        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.create_vm_cpu_affinity_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, (d, w) -> accept())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void accept() {
        boolean on = swAffinity.isChecked();
        callback.onAccepted(
            on ? affinity : new TreeMap<>(),
            swAuto.isChecked(),
            getEditText(etCapacity).trim(),
            getEditText(etClusters).trim());
    }

    private void updateVisibility() {
        boolean on = swAffinity.isChecked();
        affinityOptions.setVisibility(on ? VISIBLE : GONE);
        boolean auto = swAuto.isChecked();
        autoPreview.setVisibility(auto ? VISIBLE : GONE);
        manualInputs.setVisibility(auto ? GONE : VISIBLE);
        if (on) rebuildRows();
    }

    private void prefillIdentity() {
        affinity.clear();
        int count = Math.min(vcpuCount, hostCores.size());
        for (int i = 0; i < count; i++)
            affinity.put(i, new ArrayList<>(List.of(hostCores.get(i).index)));
    }

    /**
     * Rows are recreated only when the count changed; otherwise just their values
     * refresh, so a picker result does not flicker the whole list.
     */
    private void rebuildRows() {
        if (vcpuCount != builtVcpuCount) {
            builtVcpuCount = vcpuCount;
            rowsContainer.removeAllViews();
            for (int i = 0; i < vcpuCount; i++) {
                final int vcpu = i;
                var row = new TextRowWidget(context);
                row.setIcon(R.drawable.ic_cpu);
                row.setText(context.getString(
                    R.string.create_vm_cpu_affinity_vcpu_fmt, vcpu));
                row.setOnClickListener(v -> showCorePicker(vcpu));
                rowsContainer.addView(row);
            }
        }
        for (int i = 0; i < vcpuCount; i++) {
            var row = (TextRowWidget) rowsContainer.getChildAt(i);
            if (row == null) continue;
            row.setValue(describeBinding(i));
            row.setSubtitle(describeHosts(i));
        }
        refreshPreview();
    }

    /** {@code "CPU4-6"} for a bound vCPU, or the "not bound" label. */
    @NonNull
    private String describeBinding(int vcpu) {
        var hosts = affinity.get(vcpu);
        if (hosts == null || hosts.isEmpty())
            return context.getString(R.string.create_vm_cpu_affinity_unbound);
        // Ranges keep a wide selection inside the value column's one line, and
        // match the form the stored flag uses anyway.
        return fmt("CPU%s", CpuUtils.compactRanges(joinCsv(hosts)));
    }

    /**
     * What the binding means for the guest: the weakest core it can land on, since
     * that is the one {@link CpuPlacementPlan#deriveCapacity} reports. Null (no
     * subtitle) when unbound or when the host's tiers are unknown.
     */
    @Nullable
    private String describeHosts(int vcpu) {
        var hosts = affinity.get(vcpu);
        if (hosts == null || hosts.isEmpty()) return null;
        var weakest = weakestCore(hosts);
        if (weakest == null) return null;
        var count = hosts.size() > 1
            ? context.getString(R.string.create_vm_cpu_affinity_cores_fmt, hosts.size())
            : "";
        var text = joinNonEmpty(CpuCorePickerDialog.LABEL_SEP,
            count,
            CpuUtils.formatFreq(weakest.maxFreqKHz),
            CpuCorePickerDialog.tierLabel(context, weakest, hostTiers));
        // A single core on a host with no readable frequency has nothing to add;
        // an empty subtitle would still take a line, so drop it entirely.
        return text.isEmpty() ? null : text;
    }

    /**
     * The lowest-capacity host core among {@code hosts}, mirroring the minimum
     * {@link CpuPlacementPlan#deriveCapacity} takes. Null when none of them exists
     * on this host, which a config carried over from another device can do.
     */
    @Nullable
    private CpuUtils.CpuCore weakestCore(@NonNull List<Integer> hosts) {
        CpuUtils.CpuCore found = null;
        CpuUtils.CpuCore weakest = null;
        for (var core : hostCores) {
            if (!hosts.contains(core.index)) continue;
            if (found == null) found = core;
            if (core.capacity <= 0) continue;
            if (weakest == null || core.capacity < weakest.capacity) weakest = core;
        }
        return weakest != null ? weakest : found;
    }

    private void showCorePicker(int vcpu) {
        var hosts = affinity.get(vcpu);
        CpuCorePickerDialog.show(
            context,
            context.getString(R.string.create_vm_cpu_affinity_vcpu_fmt, vcpu),
            hosts == null ? "" : joinCsv(hosts),
            picked -> {
                // Empty selection means "no mask for this vCPU", which crosvm
                // expresses by leaving it out of the map entirely.
                if (picked.trim().isEmpty()) affinity.remove(vcpu);
                else affinity.put(vcpu, CpuUtils.parseCpuSet(picked));
                rebuildRows();
            });
    }

    private void seedManualIfEmpty() {
        var capacity = CpuPlacementPlan.deriveCapacity(affinity, hostCores);
        if (getEditText(etCapacity).trim().isEmpty())
            etCapacity.setText(CpuPlacementPlan.formatCapacity(capacity));
        if (getEditText(etClusters).trim().isEmpty()) {
            var clusters = CpuPlacementPlan.deriveClusters(capacity, vcpuCount);
            if (clusters.size() > 1)
                etClusters.setText(CpuPlacementPlan.formatClusters(clusters));
        }
    }

    /**
     * The derived values go in the subtitle, not the value column: the raw
     * {@code 0=792,1=792,...} form outgrows the value column's one ellipsized line
     * on any real core count, and the subtitle spans the row and wraps. The value
     * column keeps only what stays short.
     */
    private void refreshPreview() {
        if (!swAuto.isChecked()) return;
        var capacity = CpuPlacementPlan.deriveCapacity(affinity, hostCores);
        var clusters = CpuPlacementPlan.deriveClusters(capacity, vcpuCount);
        if (capacity.isEmpty()) {
            // No host capacity readable, so neither flag can be emitted truthfully.
            var unset = context.getString(R.string.create_vm_cpu_topology_unset);
            rowCapacityPreview.setSubtitle(unset);
            rowClusterPreview.setSubtitle(unset);
            rowClusterPreview.setValue((CharSequence) null);
            return;
        }
        rowCapacityPreview.setSubtitle(describeCapacity(capacity));
        // A lone cluster is crosvm's default, so nothing is emitted for it.
        if (clusters.size() > 1) {
            rowClusterPreview.setValue(context.getString(
                R.string.create_vm_cpu_cluster_count_fmt, clusters.size()));
            rowClusterPreview.setSubtitle(describeClusters(clusters));
        } else {
            rowClusterPreview.setValue((CharSequence) null);
            rowClusterPreview.setSubtitle(
                context.getString(R.string.create_vm_cpu_cluster_single));
        }
    }

    /** {@code "vCPU 0-5: 792    vCPU 6: 1024"} -- one group per distinct capacity. */
    @NonNull
    private String describeCapacity(@NonNull Map<Integer, Long> capacity) {
        var sb = new StringBuilder();
        for (var group : CpuPlacementPlan.groupByCapacity(capacity).entrySet()) {
            if (sb.length() > 0) sb.append(CpuCorePickerDialog.LABEL_SEP);
            sb.append(context.getString(
                R.string.create_vm_cpu_capacity_group_fmt,
                CpuUtils.compactRanges(joinCsv(group.getValue())), group.getKey()));
        }
        return sb.toString();
    }

    /** {@code "vCPU 0-5    vCPU 6"} -- the guest-visible cluster membership. */
    @NonNull
    private String describeClusters(@NonNull List<List<Integer>> clusters) {
        var sb = new StringBuilder();
        for (var cluster : clusters) {
            if (cluster.isEmpty()) continue;
            if (sb.length() > 0) sb.append(CpuCorePickerDialog.LABEL_SEP);
            sb.append(context.getString(
                R.string.create_vm_cpu_affinity_vcpu_range_fmt,
                CpuUtils.compactRanges(joinCsv(cluster))));
        }
        return sb.toString();
    }

    @NonNull
    private static String joinCsv(@NonNull Collection<Integer> values) {
        var sb = new StringBuilder();
        for (var value : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(value);
        }
        return sb.toString();
    }
}
