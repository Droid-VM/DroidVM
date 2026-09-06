// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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

    /** AUTO remains readable for old configs, but is no longer offered for new edits. */
    @Override
    public boolean isDisplay() {
        return this != AUTO;
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

    /** Resolves the legacy AUTO value at the one shared backend/device decision point. */
    @Nullable
    public static VMHypervisor resolveConfigured(
        @Nullable VMBackend backend, @Nullable VMHypervisor configured
    ) {
        return configured == null || configured == AUTO
            ? findPreferredHypervisor(backend) : configured;
    }

    /**
     * Concrete value written for a new VM. The fallback preserves the old failure mode on a
     * device with no usable hardware node (crosvm has no software accelerator), while avoiding
     * an AUTO value that can silently change meaning after the config is created.
     */
    @NonNull
    public static VMHypervisor defaultForNewVm(@NonNull VMBackend backend) {
        var resolved = findPreferredHypervisor(backend);
        if (resolved != null) return resolved;
        return backend == VMBackend.QEMU ? SOFT : KVM;
    }
}
