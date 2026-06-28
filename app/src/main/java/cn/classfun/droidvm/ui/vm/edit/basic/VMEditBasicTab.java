package cn.classfun.droidvm.ui.vm.edit.basic;

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

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.QcomChipName;
import cn.classfun.droidvm.lib.data.QcomGunyahSupports;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.LendMthpMode;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.size.SizeUnit;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class VMEditBasicTab extends VMEditBaseTab {
    private final String TAG = "VMEditBasicTab";
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
    private ChooseRowWidget choosePrepareLendMthp;
    private ChooseRowWidget chooseProtectedVm;
    private ChooseRowWidget chooseBackend;
    private ChooseRowWidget chooseHypervisor;
    private TextInputEditText etExtraOptions;

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
        choosePrepareLendMthp = view.findViewById(R.id.choose_prepare_lend_mthp);
        chooseProtectedVm = view.findViewById(R.id.choose_protected_vm);
        chooseBackend = view.findViewById(R.id.choose_backend);
        chooseHypervisor = view.findViewById(R.id.choose_hypervisor);
        etExtraOptions = view.findViewById(R.id.et_extra_options);
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

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        if (!validateInputName(store)) return false;
        if (!validateInputMemory(store)) return false;
        if (!validateInputCpu(store)) return false;
        if (!validateHypervisor(store)) return false;
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
}
