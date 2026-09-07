// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.List;

import cn.classfun.droidvm.daemon.usb.UsbRules;
import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * Which rules of one zone can never fire, because an earlier rule with the same matcher already
 * decides every device the two of them would both match.
 *
 * <p>Only a host or a sink rule shadows what is below it. Both are the last word on a device --
 * the engine acts and stops -- so a later rule with the same matcher is never even read. A VM
 * rule is not: it is skipped whenever its VM is not running or has no such controller, and the
 * search carries on to the rule below, which is exactly why someone writes one there.</p>
 *
 * <p>Only within one zone, and only for a matcher equal string for string, because that is the
 * whole of what the rules can say by themselves. A host rule for port 1.2.1 shadows a device
 * rule below it too, but only while that device is plugged into that port -- a fact about the
 * room rather than about the rules -- so it is not shown anywhere.</p>
 *
 * <p>Pure, and told nothing but the zone and its rows, so the page can recompute it on every
 * bind instead of keeping a copy that an edit or a drag could leave stale.</p>
 */
public final class UsbRuleShadow {
    private UsbRuleShadow() {
    }

    /**
     * For each rule of {@code zone}, in the order they are tried, whether an earlier one hides
     * it.
     *
     * <p>Kept per matcher rather than for the first host or sink rule alone: two devices spoken
     * for in the same zone are two independent searches, and the rule that ends one of them
     * says nothing about the other.</p>
     */
    @NonNull
    public static boolean[] unreachable(@NonNull UsbRuleLayer zone,
                                        @NonNull List<DataItem> rules) {
        var out = new boolean[rules.size()];
        // The matchers whose search has already ended above this point.
        var decided = new HashSet<List<String>>();
        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i);
            var matcher = matcherOf(zone, rule);
            out[i] = decided.contains(matcher);
            if (endsTheSearch(rule)) decided.add(matcher);
        }
        return out;
    }

    /**
     * What two rules of this zone have to agree on to be about the same device: the fields the
     * zone's rules carry, which is precisely what the daemon compares. A port rule matches on
     * its port whatever else its row happens to hold, so an id beside it is not part of it.
     *
     * <p>The catch-all zone carries neither, so every rule in it matches every other -- which is
     * right, and unreachable: the page lets that zone hold one row, and one row shadows nothing.
     * A file that somehow held two would be told the truth about the second.</p>
     */
    @NonNull
    private static List<String> matcherOf(@NonNull UsbRuleLayer zone, @NonNull DataItem rule) {
        return List.of(zone.hasId ? rule.optString("id", "") : "",
            zone.hasPort ? rule.optString("port", "") : "");
    }

    /** Whether this rule is the last word on what it matches; a VM rule never is. */
    private static boolean endsTheSearch(@NonNull DataItem rule) {
        // Read the row the way the card and the save read it, so a row that predates the target
        // key -- host unless it names a VM -- shadows here exactly as it acts in the daemon.
        var target = UsbDeviceTarget.of(rule.optString("target", null),
            rule.optString("vm", null), rule.optString("controller", null));
        return target.kind != UsbRules.Target.VM;
    }
}
