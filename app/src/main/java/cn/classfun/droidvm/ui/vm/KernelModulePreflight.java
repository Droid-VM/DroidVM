// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;
import cn.classfun.droidvm.ui.main.settings.KernelModuleManager;

/**
 * Does this VM's configuration need a host kernel module that is not loaded?
 *
 * <p>The modules under Settings are not decoration: each one supplies something a particular
 * configuration will reach for at run time, and without it the VM fails in a way that looks like
 * anything but a missing module - a guest that cannot see its own RAM, GPU allocations failing at
 * random, a big VM refusing to boot with "out of memory" on a phone with memory to spare. This
 * asks the question up front, per VM, from what the config actually turns on:
 *
 * <ul>
 *   <li>pseudo-unprotected RAM is SHARE'd to the running guest through
 *       {@code /dev/gunyah_share} - the Gunyah Host Share module;
 *   <li>GPU acceleration pins graphics memory ({@code gh_unmovable}, or the pin's migration
 *       fails and Vulkan reports out-of-device-memory) and hands it over as dma-bufs
 *       ({@code udmabuf}, which also lifts the 64 MB per-buffer cap);
 *   <li>a VM over {@value #KVCALLOC_MEMORY_MB} MB needs a page list too big to come out of one
 *       contiguous kcalloc on a fragmented phone - the kvcalloc fix.
 * </ul>
 *
 * <p><b>Which devices each rule applies to is not decided here.</b> The rules above say only what
 * the <em>configuration</em> reaches for; whether this phone has anything to load is the module
 * list's own answer, and it is already a narrow one - the KMI directory picks the build for the
 * running kernel and {@code match.json} drops the ones written for another SoC. A module the list
 * does not offer is skipped without a word: it is not a missing prerequisite but one that was
 * never part of the answer here, and the manage page would show nothing to load either. That is
 * why the kvcalloc rule needs no "and only on the 8 Gen 3" of its own - the fix is built for the
 * 6.1 GKI whose Gunyah driver has the bug, and for Qualcomm, so it can only ever surface there.
 *
 * <p>Blocking - it reads {@code /proc/modules} through root; call it off the main thread.
 */
public final class KernelModulePreflight {
    /** Module-name prefixes, as {@code match.json} keys them; the .ko adds a KMI suffix. */
    static final String GUNYAH_HOST_SHARE = "gunyah_host_share";
    static final String GH_UNMOVABLE = "gh_unmovable";
    static final String UDMABUF = "udmabuf";
    static final String GUNYAH_KVCALLOC = "gunyah_kvcalloc";

    /** Above this much guest RAM the 6.1 Gunyah driver's page list stops fitting a kcalloc. */
    static final long KVCALLOC_MEMORY_MB = 2048;

    private KernelModulePreflight() {
    }

    /** A module this VM wants, and the part of its configuration that wants it. */
    public static final class Missing {
        /** The module's title as the Kernel Module list shows it. */
        @NonNull
        public final String display;
        @StringRes
        public final int reason;

        Missing(@NonNull String display, @StringRes int reason) {
            this.display = display;
            this.reason = reason;
        }
    }

    /**
     * The modules this configuration reaches for, module prefix to the reason string, in the
     * order the dialog lists them. Pure, and deliberately device-blind: {@link #check} drops
     * whatever this phone has no build for.
     */
    @NonNull
    static LinkedHashMap<String, Integer> wantedBy(@NonNull DataItem item) {
        var wanted = new LinkedHashMap<String, Integer>();
        if (optEnum(item, "protected_vm", ProtectedVM.PROTECTED_WITHOUT_FIRMWARE)
            == ProtectedVM.PSEUDO_UNPROTECTED)
            wanted.put(GUNYAH_HOST_SHARE, R.string.vm_kernel_module_reason_pseudo_unprotected);
        if (VMScreenConfig.hasGpuDevice(item)) {
            wanted.put(GH_UNMOVABLE, R.string.vm_kernel_module_reason_gpu);
            wanted.put(UDMABUF, R.string.vm_kernel_module_reason_gpu);
        }
        if (item.optLong("memory_mb", 512) > KVCALLOC_MEMORY_MB)
            wanted.put(GUNYAH_KVCALLOC, R.string.vm_kernel_module_reason_memory);
        return wanted;
    }

    /** The wanted modules that apply to this device and are not loaded, in the order above. */
    @NonNull
    public static List<Missing> check(@NonNull Context ctx, @NonNull DataItem item) {
        var wanted = wantedBy(item);
        if (wanted.isEmpty()) return List.of();

        var modules = KernelModuleManager.list(ctx);
        var missing = new ArrayList<Missing>();
        for (var want : wanted.entrySet()) {
            KernelModuleManager.Module shipped = null;
            boolean loaded = false;
            for (var mod : modules) {
                if (!mod.name.startsWith(want.getKey())) continue;
                if (shipped == null) shipped = mod;
                if (mod.loaded) {
                    loaded = true;
                    break;
                }
            }
            if (shipped != null && !loaded)
                missing.add(new Missing(shipped.display, want.getValue()));
        }
        return missing;
    }
}
