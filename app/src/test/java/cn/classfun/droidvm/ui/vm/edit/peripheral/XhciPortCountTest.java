// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.peripheral;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.vm.VMXhciConfig;

/**
 * The picker's port-count vocabulary against the number the config stores. Nothing here can be
 * seen in the UI when it is wrong -- a shifted constant would quietly save a different geometry
 * than the one on the button -- so the mapping is asserted both ways.
 *
 * <p>The label needs a Context and is left out: the stubbed android.jar returns nothing for it.</p>
 */
public final class XhciPortCountTest {
    @Test
    public void everyCountTheConfigAllowsHasAConstantThatStandsForIt() {
        for (int ports = VMXhciConfig.MIN_PORTS; ports <= VMXhciConfig.MAX_PORTS; ports++)
            assertEquals(ports, XhciPortCount.of(ports).ports());
        assertEquals(VMXhciConfig.MAX_PORTS - VMXhciConfig.MIN_PORTS + 1,
            XhciPortCount.values().length);
    }

    @Test
    public void aStoredCountFromOutsideTheRangeLandsOnTheNearestEnd() {
        assertEquals(VMXhciConfig.MIN_PORTS, XhciPortCount.of(-3).ports());
        assertEquals(VMXhciConfig.MAX_PORTS, XhciPortCount.of(64).ports());
    }
}
