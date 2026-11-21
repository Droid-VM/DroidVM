// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.CpuPlacementPlan;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

/**
 * cpu_pin_auto: one host core per vCPU, chosen on the phone -- the strongest cores, cpu0 and cpu1
 * left to Android while the rest suffice, vCPU 0 on the weakest of those chosen. Here in
 * lib.utils because a test builds the cores itself.
 */
public final class AutoPinTest {
    /** Cores of the given capacities, cpu0 first; the frequency follows the capacity. */
    private static List<CpuUtils.CpuCore> cores(long... capacities) {
        var out = new ArrayList<CpuUtils.CpuCore>();
        for (int i = 0; i < capacities.length; i++)
            out.add(new CpuUtils.CpuCore(i, capacities[i] * 4000, 0, false, capacities[i]));
        return out;
    }

    // An 8 Elite Gen 5: six performance cores and two prime cores, all of them big.
    private static final List<CpuUtils.CpuCore> ELITE_GEN5 = cores(762, 762, 762, 762, 762, 762, 1024, 1024);
    // An 8 Gen 3: two A520s, five A720s (two of them faster), one X4.
    private static final List<CpuUtils.CpuCore> GEN3 = cores(290, 290, 755, 755, 755, 870, 870, 1024);

    @Test
    public void sixOnAnEliteGen5LikeTheRigs() {
        // The rigs' hand-made layout: 0=2:1=3:2=4:3=5:4=6:5=7.
        assertEquals("0=2:1=3:2=4:3=5:4=6:5=7",
            CpuPlacementPlan.formatAffinity(CpuPlacementPlan.autoAffinity(6, ELITE_GEN5)));
    }

    @Test
    public void fourTakeTheStrongest() {
        // The X4, the two fast A720s and one more A720 (the higher index), in core order.
        assertEquals("0=4:1=5:2=6:3=7",
            CpuPlacementPlan.formatAffinity(CpuPlacementPlan.autoAffinity(4, GEN3)));
    }

    @Test
    public void cpu0And1OnlyWhenTheRestFallShort() {
        assertEquals("0=1:1=2:2=3:3=4:4=5:5=6:6=7",
            CpuPlacementPlan.formatAffinity(CpuPlacementPlan.autoAffinity(7, GEN3)));
        assertTrue(CpuPlacementPlan.autoAffinity(9, GEN3).isEmpty());
    }

    @Test
    public void importResolvesTheFlagIntoCores() {
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        item.set("cpu_count", 6L);
        item.set(CpuPlacementPlan.KEY_AFFINITY, "");
        item.set(CpuPlacementPlan.KEY_PIN_AUTO, true);
        CpuPlacementPlan.resolveAutoPin(item, ELITE_GEN5);
        assertEquals("0=2:1=3:2=4:3=5:4=6:5=7", item.optString(CpuPlacementPlan.KEY_AFFINITY, ""));
        assertNull(item.opt(CpuPlacementPlan.KEY_PIN_AUTO, null));
    }

    @Test
    public void anAffinityOfItsOwnWins() {
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        item.set("cpu_count", 2L);
        item.set(CpuPlacementPlan.KEY_AFFINITY, "0=6:1=7");
        item.set(CpuPlacementPlan.KEY_PIN_AUTO, true);
        CpuPlacementPlan.resolveAutoPin(item, ELITE_GEN5);
        assertEquals("0=6:1=7", item.optString(CpuPlacementPlan.KEY_AFFINITY, ""));
        DataItem untouched = VMConfig.createWithCustomizeDefaults(null).item;
        CpuPlacementPlan.resolveAutoPin(untouched, ELITE_GEN5);
        assertEquals("", untouched.optString(CpuPlacementPlan.KEY_AFFINITY, ""));
    }
}
