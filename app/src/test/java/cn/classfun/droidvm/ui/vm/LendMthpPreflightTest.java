// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.lib.data.HostKernel;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.LendMthpMode;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

/**
 * The combination that cannot boot: Gunyah, a 6.1 kernel, and 256 MB LEND parcels.
 *
 * <p>Every case names its hypervisor rather than leaving it to be resolved, because resolving one
 * reads the device nodes and the answer would then be this build machine's.</p>
 */
public final class LendMthpPreflightTest {
    private static DataItem vm(String hypervisor, LendMthpMode mode) {
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        item.set("backend", "crosvm");
        item.set("hypervisor", hypervisor);
        item.set(LendMthpMode.KEY, mode);
        return item;
    }

    @Test
    public void chunkedOnGunyahUnder61IsTheCaseThatFails() {
        assertTrue(LendMthpPreflight.needsSingle(vm("gunyah", LendMthpMode.CHUNKED), "6.1"));
    }

    @Test
    public void singleIsWhatThatKernelWants() {
        assertFalse(LendMthpPreflight.needsSingle(vm("gunyah", LendMthpMode.SINGLE), "6.1"));
    }

    @Test
    public void chunkedIsTheRightAnswerOnALaterKernel() {
        // 6.6 pages a parcel in on demand, so many of them cost nothing until touched. This check
        // must not drag those VMs into a dialog.
        assertFalse(LendMthpPreflight.needsSingle(vm("gunyah", LendMthpMode.CHUNKED), "6.6"));
        assertFalse(LendMthpPreflight.needsSingle(vm("gunyah", LendMthpMode.CHUNKED), "6.12"));
    }

    @Test
    public void sixTwelveIsNotSixOne() {
        // The prefix trap, from the other side: "6.12".startsWith("6.1") is true and would have
        // put every 8 Elite Gen 5 VM in front of this dialog.
        assertEquals("6.12", HostKernel.majorMinorOf("6.12.30-android16-0-g1234"));
        assertEquals("6.1", HostKernel.majorMinorOf("6.1.75-android14-11-g5678"));
        assertNull(HostKernel.majorMinorOf(null));
        assertNull(HostKernel.majorMinorOf("not a kernel"));
    }

    @Test
    public void anUnknownKernelIsNotAccused() {
        // uname unreadable. The check explains a failure rather than predicting one, and a dialog
        // in front of a VM that would have started is worse than no dialog.
        assertFalse(LendMthpPreflight.needsSingle(vm("gunyah", LendMthpMode.CHUNKED), null));
    }

    @Test
    public void onlyGunyahLendsAnything() {
        // The mode reaches crosvm's Gunyah path alone; everywhere else it is an inert key.
        assertFalse(LendMthpPreflight.needsSingle(vm("kvm", LendMthpMode.CHUNKED), "6.1"));
        assertFalse(LendMthpPreflight.needsSingle(vm("soft", LendMthpMode.CHUNKED), "6.1"));
    }

    @Test
    public void theLegacyBooleanStillReadsAsAMode() {
        // Configs written before the enum carry true/false, which LendMthpMode migrates as
        // chunked/disabled. An imported VM is exactly the kind that predates it.
        var legacy = vm("gunyah", LendMthpMode.CHUNKED);
        legacy.set(LendMthpMode.KEY, true);
        assertTrue(LendMthpPreflight.needsSingle(legacy, "6.1"));
        legacy.set(LendMthpMode.KEY, false);
        assertFalse("disabled prepares nothing, so it lends no parcels either",
            LendMthpPreflight.needsSingle(legacy, "6.1"));
    }
}
