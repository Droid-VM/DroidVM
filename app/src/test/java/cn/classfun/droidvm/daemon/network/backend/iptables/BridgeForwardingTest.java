// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.network.backend.iptables;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.lib.store.network.UplinkMode;

/** Exercises the real command path against a stateful filter-table substitute. */
public final class BridgeForwardingTest {
    private static final String BRIDGE = "br-wifi";
    private static final String CHAIN = "droidvm_fwd_" + BRIDGE;

    private static NetworkInstance l2Network(String bridge) {
        var inst = new NetworkInstance(null); // Firewall lifecycle does not use the store.
        inst.setBridgeName(bridge);
        inst.setUplinkMode(UplinkMode.L2);
        return inst;
    }

    @Test
    public void acceptsDhcpAndNewIpv4OnlyWithinTheExactBridge() {
        var rules = new FilterTable();
        var backend = new IptablesBackend(rules);
        backend.initNetwork(l2Network(BRIDGE));
        // Initial Discover, broadcast Offer, then ordinary NEW DNS/TCP flows.
        for (var protocol : List.of("udp", "tcp", "icmp")) {
            assertEquals("ACCEPT", rules.verdict("FORWARD", BRIDGE, BRIDGE, protocol));
            assertEquals("DROP", rules.verdict("FORWARD", "br-other", "br-other", protocol));
            assertEquals("DROP", rules.verdict("FORWARD", "br-wifi2", "br-wifi2", protocol));
            assertEquals("DROP", rules.verdict("FORWARD", BRIDGE, "wlan0", protocol));
            assertEquals("DROP", rules.verdict("FORWARD", "wlan0", BRIDGE, protocol));
            assertEquals("DROP", rules.verdict("FORWARD", "br-other", BRIDGE, protocol));
        }
    }

    @Test
    public void repeatedStartAndNewDaemonReplaceStaleRulesAndDuplicateJumps() {
        var rules = new FilterTable();
        var backend = new IptablesBackend(rules);
        backend.initNetwork(l2Network(BRIDGE));
        rules.chains.get("droidvm_fwd").add(List.of("-j", CHAIN));
        rules.chains.get(CHAIN).add(List.of("-j", "ACCEPT"));
        backend.initNetwork(l2Network(BRIDGE));
        new IptablesBackend(rules).initNetwork(l2Network(BRIDGE));
        assertEquals(List.of(List.of("-j", CHAIN)), rules.chains.get("droidvm_fwd"));
        assertEquals(1, rules.chains.get(CHAIN).size());
        assertEquals("DROP", rules.verdict("FORWARD", "br-other", "br-other", "tcp"));
    }

    @Test
    public void stopRemovesAllReferencesAndRestartRestoresOnlyThisNetwork() {
        var rules = new FilterTable();
        var backend = new IptablesBackend(rules);
        backend.initNetwork(l2Network(BRIDGE));
        backend.initNetwork(l2Network("br-other"));
        rules.chains.get("droidvm_fwd").add(List.of("-j", CHAIN));
        backend.deinitNetwork(l2Network(BRIDGE));
        backend.deinitNetwork(l2Network(BRIDGE)); // repeated cleanup, including failed-start rollback
        assertFalse(rules.chains.containsKey(CHAIN));
        assertEquals("DROP", rules.verdict("FORWARD", BRIDGE, BRIDGE, "udp"));
        assertEquals("ACCEPT", rules.verdict("FORWARD", "br-other", "br-other", "tcp"));
        assertEquals(List.of(List.of("-j", "DROP")), rules.chains.get("tetherctrl_FORWARD"));
        backend.initNetwork(l2Network(BRIDGE));
        assertEquals("ACCEPT", rules.verdict("FORWARD", BRIDGE, BRIDGE, "udp"));
        assertEquals(2, rules.chains.get("droidvm_fwd").size());
    }

    @Test
    public void failedAcceptInstallationRollsBackInsteadOfReportingSuccess() {
        var rules = new FilterTable();
        rules.failure = List.of("-A", CHAIN, "-i", BRIDGE, "-o", BRIDGE, "-j", "ACCEPT");
        var backend = new IptablesBackend(rules);
        assertThrows(IllegalStateException.class, () -> backend.initNetwork(l2Network(BRIDGE)));
        assertFalse(rules.chains.containsKey(CHAIN));
        assertTrue(rules.chains.get("droidvm_fwd").isEmpty());
        assertEquals("DROP", rules.verdict("FORWARD", BRIDGE, BRIDGE, "udp"));
    }

    @Test
    public void failedJumpInstallationRollsBack() {
        var rules = new FilterTable();
        rules.failure = List.of("-A", "droidvm_fwd", "-j", CHAIN);
        var backend = new IptablesBackend(rules);
        assertThrows(IllegalStateException.class, () -> backend.initNetwork(l2Network(BRIDGE)));
        assertFalse(rules.chains.containsKey(CHAIN));
    }

    @Test
    public void globalHookIsUniqueAndPrecedesAndroidDropAfterRestart() {
        var rules = new FilterTable();
        rules.chains.get("FORWARD").add(List.of("-j", "droidvm_fwd"));
        var backend = new IptablesBackend(rules);
        assertTrue(backend.FIL_FWD.init(true));
        assertTrue(backend.FIL_FWD.init(true));
        assertEquals(List.of(List.of("-j", "droidvm_fwd"), List.of("-j", "tetherctrl_FORWARD")),
            rules.chains.get("FORWARD"));
    }

    @Test(timeout = 1000)
    public void deleteFailureAbortsWithoutAddingAnotherJump() {
        var rules = new FilterTable();
        var backend = new IptablesBackend(rules);
        rules.failure = List.of("-D", "FORWARD", "-j", "droidvm_fwd");
        assertFalse(backend.FIL_FWD.init(true));
        assertEquals(2, rules.chains.get("FORWARD").size());
    }

    private static final class FilterTable implements Predicate<String[]> {
        final Map<String, List<List<String>>> chains = new HashMap<>();
        List<String> failure;

        FilterTable() {
            chains.put("FORWARD", new ArrayList<>(List.of(
                List.of("-j", "droidvm_fwd"), List.of("-j", "tetherctrl_FORWARD"))));
            chains.put("droidvm_fwd", new ArrayList<>());
            chains.put("tetherctrl_FORWARD", new ArrayList<>(List.of(List.of("-j", "DROP"))));
        }

        @Override
        public boolean test(String[] command) {
            // L2 must not install NAT, change IPv6, or modify sysctls.
            assertEquals("iptables", command[0]);
            assertEquals("-t", command[1]);
            assertEquals("filter", command[2]);
            var args = Arrays.asList(command).subList(3, command.length);
            if (args.equals(failure)) return false;
            var op = args.get(0);
            var name = args.get(1);
            var entries = chains.get(name);
            if (op.equals("-N")) {
                if (entries != null) return false;
                chains.put(name, new ArrayList<>());
                return true;
            }
            if (entries == null) return false;
            var spec = new ArrayList<>(args.subList(2, args.size()));
            switch (op) {
                case "-F": entries.clear(); return true;
                case "-C": return entries.contains(spec);
                case "-D": return entries.remove(spec);
                case "-A": entries.add(spec); return true;
                case "-I":
                    int position = Integer.parseInt(spec.remove(0));
                    entries.add(position - 1, spec);
                    return true;
                case "-X":
                    if (!entries.isEmpty()) return false;
                    for (var list : chains.values())
                        for (var rule : list)
                            if (name.equals(value(rule, "-j"))) return false;
                    chains.remove(name);
                    return true;
                default: throw new AssertionError("Unexpected command: " + args);
            }
        }

        String verdict(String chain, String in, String out, String protocol) {
            for (var rule : chains.get(chain)) {
                if (value(rule, "-i") != null && !in.equals(value(rule, "-i"))) continue;
                if (value(rule, "-o") != null && !out.equals(value(rule, "-o"))) continue;
                if (value(rule, "-p") != null && !protocol.equals(value(rule, "-p"))) continue;
                var target = value(rule, "-j");
                if (target.equals("ACCEPT") || target.equals("DROP")) return target;
                var result = verdict(target, in, out, protocol);
                if (!result.equals("RETURN")) return result;
            }
            return "RETURN";
        }

        private static String value(List<String> rule, String flag) {
            int index = rule.indexOf(flag);
            return index < 0 ? null : rule.get(index + 1);
        }
    }
}
