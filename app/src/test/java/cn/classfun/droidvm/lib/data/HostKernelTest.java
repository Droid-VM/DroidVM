// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Reading and ordering the kernel series. The ordering is the point: every rule keyed on it
 * ("this module is for kernels before 6.18") is wrong the moment 6.6 is treated as the newer
 * of 6.6 and 6.18, which is exactly what comparing the strings would say.
 */
public final class HostKernelTest {
    @Test
    public void readsTheSeriesOutOfAReleaseString() {
        assertEquals("6.1", HostKernel.majorMinorOf("6.1.99-android14-11-g0123456789ab"));
        assertEquals("6.18", HostKernel.majorMinorOf("6.18.0-android16-5-gabcdef"));
        assertEquals("5.15", HostKernel.majorMinorOf(" 5.15.149 "));
        assertNull(HostKernel.majorMinorOf("not-a-kernel"));
        assertNull(HostKernel.majorMinorOf(null));
    }

    @Test
    public void ordersByNumberNotByText() {
        assertTrue(HostKernel.compareSeries("6.6", "6.18") < 0);
        assertTrue(HostKernel.compareSeries("6.1", "6.18") < 0);
        assertTrue(HostKernel.compareSeries("6.12", "6.18") < 0);
        assertTrue(HostKernel.compareSeries("6.18", "6.18") == 0);
        assertTrue(HostKernel.compareSeries("6.19", "6.18") > 0);
        assertTrue(HostKernel.compareSeries("7.0", "6.18") > 0);
        assertTrue(HostKernel.compareSeries("5.15", "6.1") < 0);
    }

    /** A full release string compares as its series, so a caller need not trim it first. */
    @Test
    public void comparesFullReleaseStrings() {
        assertTrue(HostKernel.compareSeries("6.6.30-android15-8-gdeadbeef", "6.18") < 0);
        assertEquals(0, (int) HostKernel.compareSeries("6.18.2-perf+", "6.18"));
    }

    /** "We do not know" stays that: it is not silently an old kernel, or a new one. */
    @Test
    public void unreadableVersionsCompareToNothing() {
        assertNull(HostKernel.compareSeries(null, "6.18"));
        assertNull(HostKernel.compareSeries("6.18", null));
        assertNull(HostKernel.compareSeries("unknown", "6.18"));
        assertNull(HostKernel.compareSeries("6.18", "sometime"));
    }
}
