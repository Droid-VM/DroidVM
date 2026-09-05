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
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.CpuPlacementDraft;
import cn.classfun.droidvm.lib.store.vm.CpuPlacementPlan;
import cn.classfun.droidvm.lib.utils.CpuUtils;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;
import cn.classfun.droidvm.ui.widgets.tools.CpuCorePickerDialog;

/**
 * Editor for where a VM's vCPUs run, and for the derived-or-manual guest
 * capacity/cluster values that describe that placement to the guest.
 *
 * <p>Two modes over the same stored fields. Simple mode is one checkbox per
 * host core: a checked core gets a vCPU of its own and the VM's CPU count is
 * whatever the checked list adds up to -- which is the placement almost every
 * VM wants and the only one whose CPU count cannot end up disagreeing with the
 * bindings. Advanced mode is the per-vCPU editor: one row per vCPU, each
 * picking any set of host cores, plus the topology section.
 *
 * <p>The mode is not stored. Which one opens is read back off the stored
 * placement ({@link CpuPlacementPlan#isOneToOne}): simple mode can only say
 * "one vCPU per host core", so anything it cannot express -- a vCPU floating
 * over several cores, an unbound vCPU, hand-written capacity/cluster values --
 * opens in advanced mode rather than being silently rewritten. Going the other
 * way is lossy by construction, so it asks first.
 *
 * <p>It hangs off the CPU count field's icon button rather than sitting inline
 * in the tab, because the row list is a function of that count: opening the
 * dialog reads the count once, so the rows can never disagree with it. (The
 * inline version had to rebuild on focus-loss, which a user could sidestep.)
 *
 * <p>Edits apply on OK only -- the working state is a copy, so Cancel is a real
 * cancel.
 */
public final class VMCpuAffinityDialog {
    /** Receives the accepted state; nothing is called on cancel. */
    public interface Callback {
        /**
         * @param draft the placement as edited. Its {@code vcpuCount} is the CPU
         *              count the field should now hold: simple mode derives it
         *              from the checked cores, every other path hands back the
         *              count the dialog was opened with.
         */
        void onAccepted(@NonNull CpuPlacementDraft draft);
    }

    private final Context context;
    private final List<CpuUtils.CpuCore> hostCores;
    /** Distinct host frequency tiers; 1 means little/big says nothing here. */
    private final int hostTiers;
    /** Working copy; the caller's map is untouched until OK. */
    private final Map<Integer, List<Integer>> affinity;
    private final Callback callback;

    /** The count the CPU field held on open; restored when nothing is pinned. */
    private final int initialVcpuCount;
    /** Working count: simple mode derives it from the checked host cores. */
    private int vcpuCount;
    /** Checked host cores in simple mode, ascending; one vCPU each. */
    private final TreeSet<Integer> simpleHosts = new TreeSet<>();
    private boolean advanced;

    private final SwitchRowWidget swAffinity;
    private final View affinityOptions;
    private final SwitchRowWidget swAdvanced;
    private final View simpleSection;
    private final TextRowWidget rowSimpleSummary;
    private final LinearLayout coreRowsContainer;
    private final View advancedSection;
    private final LinearLayout rowsContainer;
    private final SwitchRowWidget swAuto;
    private final View autoPreview;
    private final View manualInputs;
    private final TextRowWidget rowCapacityPreview;
    private final TextRowWidget rowClusterPreview;
    private final TextInputEditText etCapacity;
    private final TextInputEditText etClusters;

    private AlertDialog dialog;
    private int builtVcpuCount = -1;
    /** Set while code, not the user, moves a switch or checkbox. */
    private boolean updatingChecks;

    public VMCpuAffinityDialog(
        @NonNull Context context,
        @NonNull CpuPlacementDraft draft,
        @NonNull Callback callback
    ) {
        this.context = context;
        this.initialVcpuCount = draft.vcpuCount;
        this.vcpuCount = this.initialVcpuCount;
        this.hostCores = CpuUtils.getCores();
        this.hostTiers = CpuUtils.tierCount(this.hostCores);
        this.affinity = CpuPlacementPlan.orderedCopy(draft.affinity);
        this.callback = callback;

        var view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_vm_cpu_affinity, null);
        swAffinity = view.findViewById(R.id.sw_cpu_affinity);
        affinityOptions = view.findViewById(R.id.cpu_affinity_options);
        swAdvanced = view.findViewById(R.id.sw_cpu_affinity_advanced);
        simpleSection = view.findViewById(R.id.cpu_affinity_simple);
        rowSimpleSummary = view.findViewById(R.id.row_cpu_affinity_simple_summary);
        coreRowsContainer = view.findViewById(R.id.host_core_rows_container);
        advancedSection = view.findViewById(R.id.cpu_affinity_advanced);
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
        advanced = !simpleFits(draft.auto);
        if (!advanced)
            simpleHosts.addAll(CpuPlacementPlan.oneToOneHosts(this.affinity, this.vcpuCount));

        swAffinity.setChecked(!this.affinity.isEmpty());
        swAdvanced.setChecked(advanced);
        swAuto.setChecked(draft.auto);
        etCapacity.setText(draft.manualCapacity);
        etClusters.setText(draft.manualClusters);
        buildCoreRows();

        swAffinity.setOnCheckedChangeListener(() -> {
            if (swAffinity.isChecked()) prefillIfEmpty();
            updateVisibility();
        });
        swAdvanced.setOnCheckedChangeListener(() -> {
            if (updatingChecks) return;
            if (swAdvanced.isChecked()) enterAdvanced();
            else leaveAdvanced();
        });
        swAuto.setOnCheckedChangeListener(() -> {
            if (updatingChecks) return;
            // Going manual with empty fields: seed them with what auto produced, so
            // the user edits a working baseline instead of reconstructing it by hand.
            if (!swAuto.isChecked()) seedManualIfEmpty();
            updateVisibility();
        });
        updateVisibility();

        dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.create_vm_cpu_affinity_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, (d, w) -> accept())
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        updateOkEnabled();
    }

    private void accept() {
        boolean on = swAffinity.isChecked();
        Map<Integer, List<Integer>> result;
        if (!on) result = new TreeMap<>();
        else if (advanced) result = affinity;
        else result = CpuPlacementPlan.oneToOne(simpleHosts);
        callback.onAccepted(new CpuPlacementDraft(
            result,
            on ? vcpuCount : initialVcpuCount,
            // Simple mode hides the topology section, so it can only mean auto:
            // reporting a stale manual override it never showed would be a lie.
            !advanced || swAuto.isChecked(),
            getEditText(etCapacity).trim(),
            getEditText(etClusters).trim()));
    }

    // --- mode ---

    /**
     * Whether the working placement is one simple mode can express without
     * losing anything: a 1:1 binding onto cores this device actually has, with
     * the guest topology left to auto. Nothing pinned qualifies too -- with an
     * empty affinity the capacity/cluster values are dropped anyway.
     */
    private boolean simpleFits(boolean auto) {
        if (affinity.isEmpty()) return true;
        if (!auto) return false;
        var hosts = CpuPlacementPlan.oneToOneHosts(affinity, vcpuCount);
        if (hosts.isEmpty()) return false;
        return CpuCorePickerDialog.hostCoreIndices(hostCores).containsAll(hosts);
    }

    /** Simple -> advanced: the checked cores become the 1:1 map to edit. */
    private void enterAdvanced() {
        advanced = true;
        if (!simpleHosts.isEmpty()) {
            affinity.clear();
            affinity.putAll(CpuPlacementPlan.oneToOne(simpleHosts));
            builtVcpuCount = -1;
        }
        updateVisibility();
    }

    /**
     * Advanced -> simple. A map simple mode cannot express has to be flattened,
     * which throws away bindings the user typed, so that path confirms first --
     * and says what will be left, since "one vCPU per core" can also change the
     * VM's CPU count.
     */
    private void leaveAdvanced() {
        if (simpleFits(swAuto.isChecked())) {
            applySimple(CpuPlacementPlan.oneToOneHosts(affinity, vcpuCount), false);
            return;
        }
        var hosts = CpuPlacementPlan.flattenToOneToOne(affinity);
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.create_vm_cpu_affinity_discard_title)
            .setMessage(context.getString(
                R.string.create_vm_cpu_affinity_discard_message,
                hosts.size(), CpuUtils.compactRanges(joinCsv(hosts))))
            .setPositiveButton(android.R.string.ok, (d, w) -> applySimple(hosts, true))
            .setNegativeButton(android.R.string.cancel, (d, w) -> restoreAdvancedSwitch())
            .setOnCancelListener(d -> restoreAdvancedSwitch())
            .show();
    }

    private void applySimple(@NonNull List<Integer> hosts, boolean dropTopology) {
        advanced = false;
        simpleHosts.clear();
        simpleHosts.addAll(hosts);
        syncSimpleCount();
        if (dropTopology) {
            // The confirmation said these are gone; leaving them in the fields
            // would resurrect them on the next trip back to advanced mode.
            updatingChecks = true;
            swAuto.setChecked(true);
            updatingChecks = false;
            etCapacity.setText("");
            etClusters.setText("");
        }
        updateVisibility();
    }

    private void restoreAdvancedSwitch() {
        updatingChecks = true;
        swAdvanced.setChecked(true);
        updatingChecks = false;
    }

    /**
     * First enable with nothing bound. Either mode starts from the identity
     * (vCPU i -> host i), the mapping whose derived capacity/cluster mirror the
     * host exactly; simple mode checks as many cores as the CPU count asks for,
     * so turning the switch on does not change the count by itself.
     */
    private void prefillIfEmpty() {
        if (advanced) {
            if (affinity.isEmpty()) prefillIdentity();
            return;
        }
        if (!simpleHosts.isEmpty()) return;
        int count = Math.min(vcpuCount, hostCores.size());
        for (int i = 0; i < count; i++) simpleHosts.add(hostCores.get(i).index);
        syncSimpleCount();
    }

    /** In simple mode the CPU count is the checked list's length, nothing else. */
    private void syncSimpleCount() {
        if (!simpleHosts.isEmpty()) vcpuCount = simpleHosts.size();
    }

    private void updateVisibility() {
        boolean on = swAffinity.isChecked();
        affinityOptions.setVisibility(on ? VISIBLE : GONE);
        simpleSection.setVisibility(advanced ? GONE : VISIBLE);
        advancedSection.setVisibility(advanced ? VISIBLE : GONE);
        boolean auto = swAuto.isChecked();
        autoPreview.setVisibility(auto ? VISIBLE : GONE);
        manualInputs.setVisibility(auto ? GONE : VISIBLE);
        if (on) {
            if (advanced) rebuildRows();
            else refreshCoreRows();
        }
        updateOkEnabled();
    }

    /** Simple mode with nothing checked asks for a zero-vCPU VM; refuse it. */
    private void updateOkEnabled() {
        if (dialog == null) return;
        var ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (ok == null) return;
        ok.setEnabled(!(swAffinity.isChecked() && !advanced && simpleHosts.isEmpty()));
    }

    // --- simple mode ---

    private void buildCoreRows() {
        coreRowsContainer.removeAllViews();
        for (var core : hostCores) {
            var box = new CheckBox(context);
            box.setOnCheckedChangeListener((b, checked) -> {
                if (updatingChecks) return;
                if (checked) simpleHosts.add(core.index);
                else simpleHosts.remove(core.index);
                syncSimpleCount();
                // Unchecking a core renumbers every vCPU after it, so the whole
                // list is relabelled rather than just the row that was tapped.
                refreshCoreRows();
                updateOkEnabled();
            });
            coreRowsContainer.addView(box);
        }
    }

    /**
     * Which vCPU a core ends up as is not written on the row: the mapping is
     * simply the checked cores in order, and the label it would take does not
     * fit next to the frequency and tier on a phone-width dialog. The count
     * above the list carries the only part that is not implied by the checkbox.
     */
    private void refreshCoreRows() {
        for (int i = 0; i < hostCores.size(); i++) {
            var box = (CheckBox) coreRowsContainer.getChildAt(i);
            if (box == null) continue;
            var core = hostCores.get(i);
            boolean checked = simpleHosts.contains(core.index);
            if (box.isChecked() != checked) {
                updatingChecks = true;
                box.setChecked(checked);
                updatingChecks = false;
            }
            box.setText(CpuCorePickerDialog.label(context, core, hostTiers));
        }
        rowSimpleSummary.setValue(String.valueOf(simpleHosts.size()));
        // Only the state that blocks OK gets a subtitle; the rest speaks for itself.
        rowSimpleSummary.setSubtitle(simpleHosts.isEmpty()
            ? context.getString(R.string.create_vm_cpu_affinity_simple_empty)
            : null);
    }

    // --- advanced mode ---

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
