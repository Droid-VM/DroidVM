// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.graphics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The addresses the VNC host dropdown offers, and the only part of that dropdown that can be
 * decided without a phone.
 *
 * <p>Two are fixed and are the ends of the range: {@link #LOOPBACK}, which is the phone talking to
 * itself, and {@link #WILDCARD}, which is every address it has including ones it does not have yet.
 * Between them come the addresses it actually holds right now, which is the answer a user wants
 * when the point is "reachable from my laptop but not from the café" -- and which nobody can type
 * without going to look them up first. Anything else is still reachable through the list's last
 * entry, which the row turns into a dialog.</p>
 *
 * <p>The scanned half arrives from the daemon ({@code network_list_host_addresses}) and this class
 * only orders and de-duplicates it; the policy that decides which addresses are the phone's own --
 * and, in particular, which are a pseudo-bridged guest's parked on the uplink -- is netlink's to
 * answer and lives in {@code HostAddressScan}.</p>
 */
final class VncHostOptions {
    /** This device only. What a new VM is created with. */
    static final String LOOPBACK = "127.0.0.1";
    /**
     * Every address the phone has. Also what both backends mean by a host left unset -- crosvm's
     * {@code DEFAULT_VNC_HOST} and the qemu builder's own fallback are this string -- which is why
     * the row can show it for a config that names no host without changing what that config does.
     */
    static final String WILDCARD = "0.0.0.0";

    static final class Option {
        /** The address, exactly as it is stored and handed to the backend. */
        final String addr;
        /** The interface it was found on, or "" for the two fixed entries. */
        final String ifname;

        Option(@NonNull String addr, @NonNull String ifname) {
            this.addr = addr;
            this.ifname = ifname;
        }
    }

    private VncHostOptions() {
    }

    /**
     * Parses the daemon's {@code data} array into scan results, dropping rows it cannot read.
     * Order is netlink's dump order, which groups an interface's addresses together.
     */
    @NonNull
    static List<Option> parse(@Nullable JSONArray data) {
        var out = new ArrayList<Option>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            var row = data.optJSONObject(i);
            if (row == null) continue;
            var addr = row.optString("addr", "");
            if (addr.isEmpty()) continue;
            out.add(new Option(addr, row.optString("ifname", "")));
        }
        return out;
    }

    /**
     * The dropdown's entries: the two fixed addresses, then whatever was scanned.
     *
     * @param scanned the phone's own addresses, or an empty list before the daemon has answered --
     *                or when it cannot, which is not an error to report: the two fixed entries and
     *                the custom dialog are a working picker on their own.
     * @param current the address the row currently holds. Appended if no other rule produced it,
     *                so a stored address stays selectable when the interface it was found on is
     *                gone -- and so that what the field shows is always an entry of the list rather
     *                than a value the menu would silently fail to highlight.
     */
    @NonNull
    static List<Option> build(@NonNull List<Option> scanned, @NonNull String current) {
        var out = new ArrayList<Option>(scanned.size() + 3);
        add(out, new Option(LOOPBACK, ""));
        add(out, new Option(WILDCARD, ""));
        for (var option : scanned) add(out, option);
        if (!current.isEmpty()) add(out, new Option(current, ""));
        return Collections.unmodifiableList(out);
    }

    /**
     * Adds one entry, unless the list already offers that address. An address a scan found on two
     * interfaces, or one that is also the address the row holds, is one entry: the list names
     * addresses, and where an address came from is not something the user is choosing between.
     */
    private static void add(@NonNull List<Option> out, @NonNull Option option) {
        for (var existing : out) if (existing.addr.equals(option.addr)) return;
        out.add(option);
    }

    /**
     * Whether [host] is an address this screen can be told to listen on.
     *
     * <p>A numeric literal of either family, and nothing else. Not a name: an IPv4 listen address
     * reaches LibVNCServer as an {@code in_addr} with nowhere for a resolver to run, so crosvm
     * refuses a host it cannot parse rather than quietly listening on every address -- which means
     * a name typed here is a VM that will not start, and the place to say so is the field. It also
     * rules out the characters that would break the {@code host=...,port=...} option string the
     * value ends up inside, where the damage would show up as a command line crosvm rejects at VM
     * start rather than as a wrong address.</p>
     *
     * <p>Written out rather than handed to {@code InetAddresses.isNumericAddress}, because this is
     * the sort of rule that wants a test more than it wants a platform call, and that one is not
     * on the JVM the tests run on.</p>
     */
    static boolean isLiteral(@NonNull String host) {
        return isIpv4(host) || isIpv6(host);
    }

    /** Four dot-separated decimal octets, each 0-255 and at most three digits. */
    private static boolean isIpv4(@NonNull String s) {
        var parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (var part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            var value = 0;
            for (var i = 0; i < part.length(); i++) {
                var c = part.charAt(i);
                if (c < '0' || c > '9') return false;
                value = value * 10 + (c - '0');
            }
            if (value > 255) return false;
        }
        return true;
    }

    /**
     * Eight 16-bit groups, with at most one {@code ::} standing in for a run of zero groups and an
     * optional dotted-quad tail counting as the last two. No zone id: a scoped address is
     * link-local, and those are not offered in the first place.
     */
    private static boolean isIpv6(@NonNull String s) {
        if (s.indexOf('%') >= 0) return false;
        var gap = s.indexOf("::");
        if (gap != s.lastIndexOf("::")) return false;
        String head, tail;
        if (gap < 0) {
            head = s;
            tail = "";
        } else {
            head = s.substring(0, gap);
            tail = s.substring(gap + 2);
        }
        var counted = new int[]{0};
        // Only the half that ends the address may end in a dotted quad, and which half that is
        // depends on whether there is a gap at all: without one there is only the head.
        if (!countGroups(head, counted, gap < 0)) return false;
        if (!countGroups(tail, counted, true)) return false;
        // Without "::" the eight groups must all be spelled out; with it, the gap stands for at
        // least one, so seven explicit groups is the most that can be left.
        return gap < 0 ? counted[0] == 8 : counted[0] <= 7;
    }

    /**
     * Adds the groups of one half to {@code counted}. {@code tailAllowed} says whether this half
     * may end in a dotted quad -- only the half that ends the address may.
     */
    private static boolean countGroups(@NonNull String half, @NonNull int[] counted,
                                       boolean tailAllowed) {
        if (half.isEmpty()) return true;
        var parts = half.split(":", -1);
        for (var i = 0; i < parts.length; i++) {
            var part = parts[i];
            if (part.isEmpty()) return false;
            if (part.indexOf('.') >= 0) {
                // A dotted quad is the last two groups, and can only be the very last thing.
                if (!tailAllowed || i != parts.length - 1 || !isIpv4(part)) return false;
                counted[0] += 2;
                continue;
            }
            if (part.length() > 4) return false;
            for (var j = 0; j < part.length(); j++) {
                var c = part.charAt(j);
                var hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) return false;
            }
            counted[0]++;
        }
        return true;
    }
}
