// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.basic;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static java.lang.Integer.parseInt;
import static cn.classfun.droidvm.lib.utils.FileUtils.checkFileName;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.store.vm.ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;

import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.TextInputEditText;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.QcomChipName;
import cn.classfun.droidvm.lib.data.QcomGunyahSupports;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.CpuPlacementPlan;
import cn.classfun.droidvm.lib.store.vm.LendMthpMode;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.size.SizeUnit;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.utils.CpuUtils;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;
import cn.classfun.droidvm.ui.widgets.tools.CpuCorePickerDialog;

public final class VMEditBasicTab extends VMEditBaseTab {
    private final String TAG = "VMEditBasicTab";
    /** Matches the ti_max on input_cpu in partial_vm_edit_basic.xml. */
    private static final int MAX_VCPUS = 64;
    private TextInputRowWidget inputName;
    private TextInputRowWidget inputMemory;
    private TextInputRowWidget inputCpu;
    private TextInputRowWidget inputSwiotlb;
    private SwitchRowWidget swBalloon;
    private SwitchRowWidget swPmu;
    private SwitchRowWidget swRng;
    private SwitchRowWidget swSmt;
    private SwitchRowWidget swUsb;
    private SwitchRowWidget swSandbox;
    private SwitchRowWidget swHugepages;
    private SwitchRowWidget swDebug;
    private SwitchRowWidget swGunyahDynamicShare;
    private View gunyahDynamicShareOptions;
    private TextInputEditText etGunyahHugepageThreshold;
    private ChooseRowWidget choosePrepareLendMthp;
    private ChooseRowWidget chooseProtectedVm;
    private ChooseRowWidget chooseBackend;
    private ChooseRowWidget chooseHypervisor;
    private TextInputEditText etExtraOptions;
    private TextInputEditText etEnvironmentVariables;

    /** Host cores as reported by sysfs; read once, drives the cpuset picker. */
    private List<CpuUtils.CpuCore> hostCores = List.of();
    /**
     * vCPU affinity held for the editor dialog and for save: vCPU index to host
     * cores. Only vCPUs the user actually bound appear, matching crosvm's
     * "absent means no mask". Edited through {@link VMCpuAffinityDialog}.
     */
    private final Map<Integer, List<Integer>> affinity = new TreeMap<>();
    /** Auto-derive capacity/cluster; the dialog owns this, the tab persists it. */
    private boolean cpuTopologyAuto = true;
    /** Manual capacity/cluster overrides, only meaningful when auto is off. */
    private String manualCapacity = "";
    private String manualClusters = "";

    public VMEditBasicTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        inputName = view.findViewById(R.id.input_name);
        inputMemory = view.findViewById(R.id.input_memory);
        inputCpu = view.findViewById(R.id.input_cpu);
        inputSwiotlb = view.findViewById(R.id.input_swiotlb);
        swBalloon = view.findViewById(R.id.sw_balloon);
        swPmu = view.findViewById(R.id.sw_pmu);
        swRng = view.findViewById(R.id.sw_rng);
        swSmt = view.findViewById(R.id.sw_smt);
        swUsb = view.findViewById(R.id.sw_usb);
        swSandbox = view.findViewById(R.id.sw_sandbox);
        swHugepages = view.findViewById(R.id.sw_hugepages);
        swDebug = view.findViewById(R.id.sw_debug);
        swGunyahDynamicShare = view.findViewById(R.id.sw_gunyah_dynamic_share);
        gunyahDynamicShareOptions = view.findViewById(R.id.gunyah_dynamic_share_options);
        etGunyahHugepageThreshold = view.findViewById(R.id.et_gunyah_hugepage_threshold);
        choosePrepareLendMthp = view.findViewById(R.id.choose_prepare_lend_mthp);
        chooseProtectedVm = view.findViewById(R.id.choose_protected_vm);
        chooseBackend = view.findViewById(R.id.choose_backend);
        chooseHypervisor = view.findViewById(R.id.choose_hypervisor);
        etExtraOptions = view.findViewById(R.id.et_extra_options);
        etEnvironmentVariables = view.findViewById(R.id.et_environment_variables);
    }

    @Override
    public void initValue() {
        inputMemory.setValue(512, SizeUnit.MB);
        inputCpu.setValue(1);
        inputSwiotlb.setValue(64, SizeUnit.MB);
        swDebug.setChecked(false);
        chooseProtectedVm.configure(ProtectedVM.class, PROTECTED_WITHOUT_FIRMWARE);
        chooseBackend.configure(VMBackend.class, VMBackend.DEFAULT);
        chooseHypervisor.configure(VMHypervisor.class, VMHypervisor.DEFAULT);
        choosePrepareLendMthp.configure(LendMthpMode.class, LendMthpMode.CHUNKED);
        parent.put("backend", VMBackend.DEFAULT);
        parent.put("hypervisor", VMHypervisor.DEFAULT);
        chooseBackend.setOnValueChangedListener((oldValue, newValue) -> parent.put("backend", newValue));
        chooseHypervisor.setOnValueChangedListener((oldValue, newValue) -> parent.put("hypervisor", newValue));
        swGunyahDynamicShare.setOnCheckedChangeListener(this::updateGunyahVisibility);
        chooseProtectedVm.setOnValueChangedListener((oldValue, newValue) -> updateProtectedVisibility());
        updateGunyahVisibility();
        updateProtectedVisibility();
        initCpuTopology();
        try {
            var socModel = QcomChipName.getCurrentSoC();
            var gunyah = new QcomGunyahSupports(parent);
            if (gunyah.isCapacitySupported(socModel, "no_mthp"))
                choosePrepareLendMthp.setSelectedItem(LendMthpMode.DISABLED);
            if (gunyah.isCapacitySupported(socModel, "mthp_chunked"))
                choosePrepareLendMthp.setSelectedItem(LendMthpMode.CHUNKED);
            if (gunyah.isCapacitySupported(socModel, "mthp_single"))
                choosePrepareLendMthp.setSelectedItem(LendMthpMode.SINGLE);
        } catch (Exception e) {
            Log.w(TAG, "failed to load soc capacity", e);
        }
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        var item = config.item;
        inputName.setText(config.getName());
        inputMemory.setValue(item.optLong("memory_mb", 512), SizeUnit.MB);
        inputCpu.setValue(item.optLong("cpu_count", 1));
        inputSwiotlb.setValue(item.optLong("swiotlb_mb", 64), SizeUnit.MB);
        swBalloon.setChecked(item.optBoolean("balloon", false));
        swPmu.setChecked(item.optBoolean("pmu", false));
        swRng.setChecked(item.optBoolean("rng", false));
        swSmt.setChecked(item.optBoolean("smt", false));
        swUsb.setChecked(item.optBoolean("usb", false));
        swSandbox.setChecked(item.optBoolean("sandbox", false));
        swHugepages.setChecked(item.optBoolean("hugepages", false));
        swDebug.setChecked(item.optBoolean("strace", false));
        swGunyahDynamicShare.setChecked(item.optBoolean("gunyah_dynamic_share", false));
        etGunyahHugepageThreshold.setText(String.valueOf(
            item.optLong("gunyah_hugepage_threshold_kb", 1024)));
        choosePrepareLendMthp.setSelectedItem(LendMthpMode.fromItem(item));
        chooseProtectedVm.setSelectedItem(optEnum(item, "protected_vm", PROTECTED_WITHOUT_FIRMWARE));
        chooseBackend.setSelectedItem(optEnum(item, "backend", VMBackend.DEFAULT));
        chooseHypervisor.setSelectedItem(optEnum(item, "hypervisor", VMHypervisor.DEFAULT));
        var extraOpts = item.opt("extra_options", null);
        if (extraOpts != null && extraOpts.is(DataItem.Type.ARRAY)) {
            var sb = new StringBuilder();
            for (int i = 0; i < extraOpts.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(extraOpts.optString(i, ""));
            }
            etExtraOptions.setText(sb.toString());
        }
        var environmentVariables = item.opt("environment_variables", null);
        if (environmentVariables != null && environmentVariables.is(DataItem.Type.ARRAY)) {
            var sb = new StringBuilder();
            for (int i = 0; i < environmentVariables.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(environmentVariables.optString(i, ""));
            }
            etEnvironmentVariables.setText(sb.toString());
        } else {
            etEnvironmentVariables.setText("");
        }
        loadCpuTopology(item);
        updateGunyahVisibility();
        updateProtectedVisibility();
    }

    private void loadCpuTopology(@NonNull DataItem item) {
        affinity.clear();
        affinity.putAll(CpuPlacementPlan.parseAffinity(
            item.optString(CpuPlacementPlan.KEY_AFFINITY, "")));
        cpuTopologyAuto = item.optBoolean(CpuPlacementPlan.KEY_AUTO, true);
        manualCapacity = item.optString(CpuPlacementPlan.KEY_CAPACITY, "");
        manualClusters = item.optString(CpuPlacementPlan.KEY_CLUSTERS, "");
    }

    /**
     * Wires up CPU placement: the vCPU affinity editor behind the CPU count field's
     * icon button. The affinity, capacity and cluster flags are one decision from
     * three sides -- affinity pins vCPU threads to host cores, capacity and cluster
     * describe that placement to the guest via the FDT -- so they are edited together
     * in {@link VMCpuAffinityDialog}.
     */
    private void initCpuTopology() {
        hostCores = CpuUtils.getCores();
        inputCpu.setIconButtonOnClickListener(this::showAffinityDialog);
    }

    /**
     * Opens the affinity editor for the vCPU count as currently entered. Reading
     * the count here rather than tracking edits is the point of the dialog: the
     * row list cannot disagree with the field.
     */
    private void showAffinityDialog() {
        new VMCpuAffinityDialog(
            parent, currentVcpuCount(), affinity, cpuTopologyAuto,
            manualCapacity, manualClusters,
            (newAffinity, auto, capacity, clusters) -> {
                affinity.clear();
                affinity.putAll(newAffinity);
                cpuTopologyAuto = auto;
                manualCapacity = capacity;
                manualClusters = clusters;
            });
    }

    // Gunyah dynamic memory sharing is a hypervisor-level memory-sharing mechanism (the GPU is
    // just its first user), so it sits with the other lend/share options rather than in the
    // graphics tab where it used to live.
    /**
     * Show the SWIOTLB size only where a VM can use one.
     *
     * A bounce pool exists because the hypervisor has taken the guest's memory away from the
     * host: virtio has to hand the host buffers it can still reach. An unprotected VM never lost
     * that access, and a pseudo-unprotected one gets it back before the payload runs, so in both
     * the field would ask for memory nothing would ever bounce through -- and in the second it
     * would actively hurt, putting a restricted-dma-pool node in the tree of a guest kernel that
     * was never built to honour one. The backend ignores the stored value in those modes; this
     * keeps the field from claiming otherwise.
     */
    private void updateProtectedVisibility() {
        var pvm = chooseProtectedVm.getSelectedItem();
        boolean bounces = pvm == ProtectedVM.PROTECTED_PROTECTED
            || pvm == ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;
        inputSwiotlb.setVisibility(bounces ? VISIBLE : GONE);
    }

    private void updateGunyahVisibility() {
        boolean enabled = swGunyahDynamicShare.isChecked();
        gunyahDynamicShareOptions.setVisibility(enabled ? VISIBLE : GONE);
        // Publish it: features in other tabs (guest-alloc vram today, more later) cannot work
        // without dynamic sharing and check this before letting the VM be saved.
        parent.put(VMEditActivity.SHARED_GUNYAH_DYNAMIC_SHARE, enabled);
    }

    /** vCPU count as currently typed, clamped to the field's own 1..64 range. */
    private int currentVcpuCount() {
        try {
            var text = inputCpu.getText().trim();
            if (text.isEmpty()) return 1;
            return Math.max(1, Math.min(parseInt(text), MAX_VCPUS));
        } catch (Exception ignored) {
            return 1;
        }
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

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkInputField(
        @NonNull TextInputEditText field,
        boolean allowEmpty, int min, int max
    ) {
        field.setError(null);
        try {
            var text = getEditText(field);
            if (text.isEmpty() && allowEmpty) return true;
            var ret = parseInt(text);
            if (ret < min || ret > max)
                throw new IllegalArgumentException();
            return true;
        } catch (Exception ignored) {
            field.setError(parent.getString(R.string.create_vm_error_invalid_number));
            return false;
        }
    }

    private boolean validateInputName(@NonNull VMStore store) {
        inputName.setError(null);
        var name = inputName.getText();
        if (TextUtils.isEmpty(name)) {
            inputName.setError(parent.getString(R.string.create_vm_error_name_empty));
            return false;
        }
        if (!checkFileName(name)) {
            inputName.setError(parent.getString(R.string.create_vm_error_name_invalid));
            return false;
        }
        if (!store.isNameUnique(name, parent.editVMId)) {
            inputName.setError(parent.getString(R.string.create_vm_error_name_duplicate));
            return false;
        }
        return true;
    }

    private boolean validateInputMemory(@NonNull VMStore ignored) {
        inputMemory.setError(null);
        if (!inputMemory.isInputValid()) {
            inputMemory.setError(parent.getString(R.string.create_vm_error_invalid_number));
            return false;
        }
        try {
            inputMemory.getValue(SizeUnit.MB);
        } catch (Exception ignored2) {
            inputMemory.setError(parent.getString(R.string.create_vm_error_invalid_number));
            return false;
        }
        return true;
    }

    private boolean validateInputCpu(@NonNull VMStore ignored) {
        inputCpu.setError(null);
        if (!inputCpu.isInputValid()) {
            inputCpu.setError(parent.getString(R.string.create_vm_error_invalid_number));
            return false;
        }
        try {
            inputCpu.getValue();
        } catch (Exception ignored2) {
            inputCpu.setError(parent.getString(R.string.create_vm_error_invalid_number));
            return false;
        }
        return true;
    }

    private boolean validateHypervisor(@NonNull VMStore ignored) {
        VMBackend backend = chooseBackend.getSelectedItem();
        VMHypervisor hypervisor = chooseHypervisor.getSelectedItem();
        if (!VMHypervisor.isBackendSupported(backend, hypervisor))
            return showValidateFailed(R.string.create_vm_error_hypervisor_not_supported);
        return true;
    }

    private boolean validateEnvironmentVariables() {
        etEnvironmentVariables.setError(null);
        for (var line : getEditText(etEnvironmentVariables).split("\n")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            var separator = trimmed.indexOf('=');
            if (separator <= 0 || trimmed.substring(0, separator).trim().isEmpty()) {
                etEnvironmentVariables.setError(
                    parent.getString(R.string.create_vm_error_invalid_environment_variable));
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        if (!validateInputName(store)) return false;
        if (!validateInputMemory(store)) return false;
        if (!validateInputCpu(store)) return false;
        if (!validateHypervisor(store)) return false;
        if (!validateEnvironmentVariables()) return false;
        if (!checkInputField(etGunyahHugepageThreshold, false, 64, 1048576)) return false;
        if (!validateCpuTopology()) return false;
        return true;
    }

    /**
     * The affinity dialog already keeps its own edits in range, so this mostly
     * guards a stored config that was hand-edited, or a CPU count lowered after
     * the affinity was set.
     */
    private boolean validateCpuTopology() {
        if (!affinity.isEmpty()) {
            int count = currentVcpuCount();
            var hostIdx = CpuCorePickerDialog.hostCoreIndices(hostCores);
            for (var entry : affinity.entrySet()) {
                if (entry.getKey() >= count)
                    return showValidateFailed(R.string.create_vm_error_cpu_affinity_vcpu_oob);
                for (var host : entry.getValue())
                    if (!hostIdx.contains(host))
                        return showValidateFailed(parent.getString(
                            R.string.create_vm_error_cpu_affinity_host_oob, host));
            }
            if (!cpuTopologyAuto && !validateManualTopology(count)) return false;
        }
        return true;
    }

    /**
     * Hand-written capacity/cluster strings. Capacity is only range-checked;
     * clusters additionally must not repeat a vCPU, which crosvm rejects
     * ("CPU index must be unique").
     */
    private boolean validateManualTopology(int vcpuCount) {
        var capacityText = manualCapacity.trim();
        if (!capacityText.isEmpty()) {
            var capacity = CpuPlacementPlan.parseCapacity(capacityText);
            if (capacity.isEmpty())
                return showValidateFailed(R.string.create_vm_error_cpu_capacity_invalid);
            for (var entry : capacity.entrySet()) {
                if (entry.getKey() >= vcpuCount)
                    return showValidateFailed(R.string.create_vm_error_cpu_affinity_vcpu_oob);
                if (entry.getValue() > CpuUtils.MAX_CAPACITY)
                    return showValidateFailed(R.string.create_vm_error_cpu_capacity_invalid);
            }
        }
        var clusters = CpuPlacementPlan.parseClusters(manualClusters.trim());
        var overlaps = CpuPlacementPlan.findClusterOverlaps(clusters);
        if (!overlaps.isEmpty())
            return showValidateFailed(parent.getString(
                R.string.create_vm_error_cpu_clusters_overlap, joinCsv(overlaps)));
        for (var cluster : clusters)
            for (var vcpu : cluster)
                if (vcpu >= vcpuCount)
                    return showValidateFailed(R.string.create_vm_error_cpu_affinity_vcpu_oob);
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        var item = config.item;
        config.setName(inputName.getText());
        item.set("memory_mb", inputMemory.getValue(SizeUnit.MB));
        item.set("cpu_count", inputCpu.getValue());
        item.set("swiotlb_mb", inputSwiotlb.getValue(SizeUnit.MB));
        item.set("balloon", swBalloon.isChecked());
        item.set("pmu", swPmu.isChecked());
        item.set("rng", swRng.isChecked());
        item.set("smt", swSmt.isChecked());
        item.set("usb", swUsb.isChecked());
        item.set("sandbox", swSandbox.isChecked());
        item.set("hugepages", swHugepages.isChecked());
        item.set("strace", swDebug.isChecked());
        item.set("gunyah_dynamic_share", swGunyahDynamicShare.isChecked());
        item.set("gunyah_hugepage_threshold_kb", parseInt(getEditText(etGunyahHugepageThreshold)));
        LendMthpMode lendMthpMode = choosePrepareLendMthp.getSelectedItem();
        item.set(LendMthpMode.KEY, lendMthpMode);
        ProtectedVM pvm = chooseProtectedVm.getSelectedItem();
        item.set("protected_vm", pvm);
        VMBackend backend = chooseBackend.getSelectedItem();
        item.set("backend", backend);
        VMHypervisor hypervisor = chooseHypervisor.getSelectedItem();
        item.set("hypervisor", hypervisor);
        var arr = DataItem.newArray();
        var text = getEditText(etExtraOptions);
        for (var line : text.split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty())
                arr.append(DataItem.newString(trimmed));
        }
        item.set("extra_options", arr);

        var environmentVariables = DataItem.newArray();
        var environmentText = getEditText(etEnvironmentVariables);
        for (var line : environmentText.split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty())
                environmentVariables.append(DataItem.newString(trimmed));
        }
        item.set("environment_variables", environmentVariables);
        saveCpuTopology(item);
    }

    private void saveCpuTopology(@NonNull DataItem item) {
        // An empty affinity string is how CpuPlacementPlan is told to emit no CPU
        // placement flags at all; the dialog returns an empty map when turned off.
        item.set(CpuPlacementPlan.KEY_AFFINITY, CpuPlacementPlan.formatAffinity(affinity));
        item.set(CpuPlacementPlan.KEY_AUTO, cpuTopologyAuto);
        item.set(CpuPlacementPlan.KEY_CAPACITY, manualCapacity);
        item.set(CpuPlacementPlan.KEY_CLUSTERS, manualClusters);
    }

    /**
     * The vCPU affinity as currently edited, so the graphics tab can warn when the
     * GPU worker cpuset overlaps it. Read-only view: the dialog owns the edits.
     */
    @NonNull
    public Map<Integer, List<Integer>> getCurrentAffinity() {
        return affinity;
    }

    /**
     * The protection mode as currently selected (before save), so other
     * tabs can warn about protected-VM constraints without re-reading the
     * stored config.
     */
    @Nullable
    public ProtectedVM getCurrentProtectedVm() {
        return chooseProtectedVm.getSelectedItem();
    }

    /** The backend as currently selected */
    @NonNull
    public VMBackend getCurrentBackend() {
        var backend = chooseBackend.<VMBackend>getSelectedItem();
        return backend == null ? VMBackend.DEFAULT : backend;
    }
}
