// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.graphics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.ui.vm.edit.graphics.VncHostOptions.Option;

/**
 * The VNC host dropdown's list: what it offers, in what order, and what it will not let through.
 *
 * <p>The half of that dropdown that needs neither a phone nor a daemon. Which addresses a scan
 * finds is netlink's answer and the daemon's to filter; what the list does with the answer is
 * here.</p>
 */
public class VncHostOptionsTest {
    @Test
    public void offersTheTwoFixedEntriesWithNothingScanned() {
        assertAddrs(VncHostOptions.build(List.of(), "127.0.0.1"), "127.0.0.1", "0.0.0.0");
    }

    @Test
    public void putsScannedAddressesAfterTheFixedOnes() {
        var scanned = List.of(
            new Option("192.168.66.87", "wlan0"),
            new Option("2a0e:b107:1953:cc::1", "wlan0"));
        assertAddrs(VncHostOptions.build(scanned, "127.0.0.1"),
            "127.0.0.1", "0.0.0.0", "192.168.66.87", "2a0e:b107:1953:cc::1");
    }

    @Test
    public void namesEachAddressOnce() {
        // The same address on two interfaces, and one that repeats what a fixed entry already
        // offers: the list is of addresses, not of the rules that produced them.
        var scanned = List.of(
            new Option("192.168.66.87", "wlan0"),
            new Option("192.168.66.87", "wlan1"),
            new Option("0.0.0.0", "eth0"));
        assertAddrs(VncHostOptions.build(scanned, "127.0.0.1"),
            "127.0.0.1", "0.0.0.0", "192.168.66.87");
    }

    @Test
    public void keepsAStoredAddressTheScanDidNotFind() {
        // The interface it was found on is gone, or it was typed. Either way the row shows it, so
        // it has to be in the list -- otherwise the menu highlights nothing and a save that only
        // meant to change the port would look like it had lost the address.
        assertAddrs(VncHostOptions.build(List.of(), "10.0.0.5"),
            "127.0.0.1", "0.0.0.0", "10.0.0.5");
    }

    @Test
    public void addsNothingForAnEmptySelection() {
        // "" is a config that names no host, which the row shows as the wildcard -- an entry the
        // list already has. It must not become an entry of its own.
        assertAddrs(VncHostOptions.build(List.of(), ""), "127.0.0.1", "0.0.0.0");
    }

    @Test
    public void takesEveryShapeOfAddressLiteral() {
        for (var ok : new String[]{
            "0.0.0.0", "127.0.0.1", "192.168.66.87", "255.255.255.255",
            "::", "::1", "2a0e:b107:1953:cc:436f:dd61:fb74:7c7f",
            "2a0e:b107::1", "fe80::32d5:be4e:8bf0:9f41", "1:2:3:4:5:6:7:8",
            "1:2:3:4:5:6:7::", "::ffff:192.168.66.87", "64:ff9b::192.0.2.1",
        })
            assertTrue(ok, VncHostOptions.isLiteral(ok));
    }

    @Test
    public void refusesAnythingThatIsNotOne() {
        for (var bad : new String[]{
            // Not an address at all. A name is refused on purpose: crosvm hands an IPv4 listen
            // address to LibVNCServer as an in_addr with nowhere to resolve one, so a name here is
            // a VM that will not start.
            "", " ", "phone.lan", "localhost",
            // Would break the "host=...,port=..." option string it ends up inside.
            "10.0.0.1,port=1", "10.0.0.1 ", "[::1]", "a\"b", "a'b",
            // Malformed v4.
            "1.2.3", "1.2.3.4.5", "256.0.0.1", "1.2.3.", "01234.0.0.1",
            // Malformed v6: two gaps, too many groups, too few, a bad group, a zone id, and a
            // dotted quad somewhere other than the end.
            "1::2::3", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:12345",
            "fe80::1%wlan0", "::1.2.3.4:5", "1:2:3:4:5:6:7:8::",
        })
            assertFalse(bad, VncHostOptions.isLiteral(bad));
    }

    private static void assertAddrs(@NonNull List<Option> actual, @NonNull String... expected) {
        var addrs = new ArrayList<String>(actual.size());
        for (var option : actual) addrs.add(option.addr);
        assertEquals(List.of(expected), addrs);
    }
}
