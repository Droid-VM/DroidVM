package cn.classfun.droidvm.ui.vm.edit.basic;

import static cn.classfun.droidvm.lib.utils.FileUtils.checkFileName;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.store.vm.ProtectedVM.PROTECTED_WITHOUT_FIRMWARE;

import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.size.SizeUnit;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.widgets.row.ChooseRowWidget;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class VMEditBasicTab extends VMEditBaseTab {
    private TextInputRowWidget inputName;
    private TextInputRowWidget inputMemory;
    private TextInputRowWidget inputCpu;
    private SwitchRowWidget swBalloon;
    private SwitchRowWidget swPmu;
    private SwitchRowWidget swRng;
    private SwitchRowWidget swSmt;
    private SwitchRowWidget swUsb;
    private SwitchRowWidget swSandbox;
    private SwitchRowWidget swHugepages;
    private ChooseRowWidget chooseProtectedVm;
    private ChooseRowWidget chooseBackend;

    public VMEditBasicTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        inputName = view.findViewById(R.id.input_name);
        inputMemory = view.findViewById(R.id.input_memory);
        inputCpu = view.findViewById(R.id.input_cpu);
        swBalloon = view.findViewById(R.id.sw_balloon);
        swPmu = view.findViewById(R.id.sw_pmu);
        swRng = view.findViewById(R.id.sw_rng);
        swSmt = view.findViewById(R.id.sw_smt);
        swUsb = view.findViewById(R.id.sw_usb);
        swSandbox = view.findViewById(R.id.sw_sandbox);
        swHugepages = view.findViewById(R.id.sw_hugepages);
        chooseProtectedVm = view.findViewById(R.id.choose_protected_vm);
        chooseBackend = view.findViewById(R.id.choose_backend);
    }

    @Override
    public void initValue() {
        inputMemory.setValue(512, SizeUnit.MB);
        inputCpu.setValue(1);
        chooseProtectedVm.configure(ProtectedVM.class, PROTECTED_WITHOUT_FIRMWARE);
        chooseBackend.configure(VMBackend.class, VMBackend.CROSVM);
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        var item = config.item;
        inputName.setText(config.getName());
        inputMemory.setValue(item.optLong("memory_mb", 512), SizeUnit.MB);
        inputCpu.setValue(item.optLong("cpu_count", 1));
        swBalloon.setChecked(item.optBoolean("balloon", false));
        swPmu.setChecked(item.optBoolean("pmu", false));
        swRng.setChecked(item.optBoolean("rng", false));
        swSmt.setChecked(item.optBoolean("smt", false));
        swUsb.setChecked(item.optBoolean("usb", false));
        swSandbox.setChecked(item.optBoolean("sandbox", false));
        swHugepages.setChecked(item.optBoolean("hugepages", false));
        chooseProtectedVm.setSelectedItem(optEnum(item, "protected_vm", PROTECTED_WITHOUT_FIRMWARE));
        chooseBackend.setSelectedItem(optEnum(item, "backend", VMBackend.DEFAULT));
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

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        if (!validateInputName(store)) return false;
        if (!validateInputMemory(store)) return false;
        if (!validateInputCpu(store)) return false;
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        var item = config.item;
        config.setName(inputName.getText());
        item.set("memory_mb", inputMemory.getValue(SizeUnit.MB));
        item.set("cpu_count", inputCpu.getValue());
        item.set("balloon", swBalloon.isChecked());
        item.set("pmu", swPmu.isChecked());
        item.set("rng", swRng.isChecked());
        item.set("smt", swSmt.isChecked());
        item.set("usb", swUsb.isChecked());
        item.set("sandbox", swSandbox.isChecked());
        item.set("hugepages", swHugepages.isChecked());
        ProtectedVM pvm = chooseProtectedVm.getSelectedItem();
        item.set("protected_vm", pvm);
        VMBackend backend = chooseBackend.getSelectedItem();
        item.set("backend", backend);
    }
}
