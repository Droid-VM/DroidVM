// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.lib.data.HostKernel;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.LendMthpMode;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;

/**
 * Can this kernel LEND the way this VM is configured to?
 *
 * <p>Guest RAM is handed to Gunyah as parcels. {@code chunked} splits the prepared region into
 * 256 MB ones and lends each in its own slot; {@code single} lends the whole region at once. Which
 * of the two works is not a preference but a property of the resource manager on the other side,
 * and it changed between GKI series: 6.6 demand-pages a parcel, so many of them cost nothing until
 * they are touched, while 6.1 commits every parcel as it arrives and answers the second or third
 * one with NORESOURCE. A 3 GB VM is twelve parcels there, and it does not boot.</p>
 *
 * <p>The failure names none of this. crosvm reports {@code GH_VM_START failed} and exits with
 * "failed to initialize virtual machine: No such device", which reads like a missing kernel module
 * or a broken image, and the setting behind it is three tabs away in the editor.</p>
 *
 * <p>Worth a pre-start check rather than a better default because the value travels. A new VM gets
 * the right mode from the device capability table, but a VM imported from a package carries the
 * mode of the phone it was exported from -- and a package built on a 6.6 phone brings
 * {@code chunked} to a 6.1 one, where it cannot work. That is the case this exists for.</p>
 *
 * <p>{@link #check} runs {@code uname}: call it off the main thread.</p>
 */
public final class LendMthpPreflight {
    private LendMthpPreflight() {
    }

    /**
     * Is this VM configured to lend in 256 MB parcels through Gunyah?
     *
     * <p>Half the question, and the half that does not need the device. The other hypervisors do
     * not lend at all -- the mode is passed to crosvm's Gunyah path alone -- so the setting is
     * inert everywhere else and there is nothing to warn about.</p>
     */
    static boolean lendsChunked(@NonNull DataItem item) {
        var backend = optEnum(item, "backend", VMBackend.DEFAULT);
        var hypervisor = VMHypervisor.resolveConfigured(
            backend, optEnum(item, "hypervisor", VMHypervisor.DEFAULT));
        return hypervisor == VMHypervisor.GUNYAH
            && LendMthpMode.fromItem(item) == LendMthpMode.CHUNKED;
    }

    /**
     * Whether [item] would fail to start on a kernel of series [kernelMajorMinor].
     *
     * <p>Split out from {@link #check} so the rule can be read and tested without a phone under
     * it. An unknown kernel answers false: the check exists to explain a failure, and inventing one
     * for a kernel nobody could identify would put a dialog in front of a VM that starts fine.</p>
     */
    static boolean needsSingle(@NonNull DataItem item, @Nullable String kernelMajorMinor) {
        return HostKernel.GKI_6_1.equals(kernelMajorMinor) && lendsChunked(item);
    }

    /** {@link #needsSingle} against the running kernel. Runs {@code uname}; never throws. */
    public static boolean check(@NonNull DataItem item) {
        try {
            return needsSingle(item, HostKernel.majorMinor());
        } catch (Exception e) {
            return false;
        }
    }
}
