package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum VMHypervisor implements StringEnum {
    AUTO(R.string.create_vm_hypervisor_auto, null),
    SOFT(R.string.create_vm_hypervisor_soft, null),
    KVM(R.string.create_vm_hypervisor_kvm, "/dev/kvm"),
    GUNYAH(R.string.create_vm_hypervisor_gunyah, "/dev/gunyah"),
    GENIEZONE(R.string.create_vm_hypervisor_geniezone, "/dev/gzvm");

    public static final VMHypervisor DEFAULT = AUTO;

    private final @StringRes int stringId;
    private final String devicePath;

    VMHypervisor(@StringRes int stringId, @Nullable String devicePath) {
        this.stringId = stringId;
        this.devicePath = devicePath;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }

    @Nullable
    public String getDevicePath() {
        return devicePath;
    }

    public boolean isSupported() {
        return devicePath == null || shellCheckExists(devicePath);
    }

    public static boolean isBackendSupported(VMBackend backend, VMHypervisor hypervisor) {
        if (backend == null || hypervisor == AUTO) return true;
        switch (backend) {
            case QEMU: switch (hypervisor) {
                case SOFT:
                case KVM:
                case GUNYAH:
                    return true;
            } break;
            case CROSVM: switch (hypervisor) {
                case KVM:
                case GUNYAH:
                case GENIEZONE:
                    return true;
            } break;
        }
        return false;
    }

    @Nullable
    public static VMHypervisor findPreferredHypervisor(
        @Nullable VMBackend backend,
        @NonNull List<VMHypervisor> allowed
    ) {
        for (var hypervisor : allowed) {
            if (hypervisor.getDevicePath() == null) continue;
            if (!isBackendSupported(backend, hypervisor)) continue;
            if (!hypervisor.isSupported()) continue;
            return hypervisor;
        }
        if (allowed.contains(SOFT) && isBackendSupported(backend, SOFT))
            return SOFT;
        return null;
    }

    @Nullable
    public static VMHypervisor findPreferredHypervisor(@Nullable VMBackend backend) {
        return findPreferredHypervisor(backend, List.of(values()));
    }
}
