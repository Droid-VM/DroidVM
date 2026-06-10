package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.network.NetworkInstanceStore;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * Installs host -> guest iptables DNAT rules for one VM's {@code port_forwards}, resolving the
 * guest IP from DHCP leases / the neighbor table while the VM is RUNNING.
 */
final class VMPortForwarder {
    private static final String TAG = "VMPortForwarder";
    private static final int RESOLVE_MAX_ATTEMPTS = 30;
    private static final long RESOLVE_INTERVAL_MS = 1000L;

    private final VMInstance vm;
    private final NetworkInstanceStore networkStore;
    private final List<Applied> applied = new ArrayList<>();
    private volatile boolean running = false;
    private Thread thread;

    VMPortForwarder(@NonNull VMInstance vm, @Nullable NetworkInstanceStore networkStore) {
        this.vm = vm;
        this.networkStore = networkStore;
    }

    boolean hasRules() {
        var pf = vm.item.opt("port_forwards", null);
        return pf != null && !pf.isEmpty();
    }

    synchronized void start() {
        if (running) return;
        if (!hasRules()) return;
        if (networkStore == null) {
            Log.w(TAG, fmt("VM %s has port forwards but no network store", vm.getName()));
            return;
        }
        running = true;
        thread = new Thread(this::loop, fmt("PF-%s", vm.getId()));
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        notifyAll();
        var t = thread;
        if (t != null) {
            t.interrupt();
            thread = null;
        }
        removeAll();
    }

    @NonNull
    JSONArray snapshotApplied() {
        var arr = new JSONArray();
        synchronized (applied) {
            for (var a : applied) {
                try {
                    var o = new JSONObject();
                    o.put("protocol", a.protocol);
                    o.put("host_ip", a.hostIp == null ? "" : a.hostIp);
                    o.put("host_port", a.hostPort);
                    o.put("guest_ip", a.guestIp);
                    o.put("guest_port", a.guestPort);
                    arr.put(o);
                } catch (JSONException ignored) {
                }
            }
        }
        return arr;
    }

    /** Wakes the poll thread (or starts it) so reconcile always runs on that single thread. */
    synchronized void sync() {
        if (running) notifyAll();
        else start();
    }

    /**
     * Incrementally reconciles installed rules to the current config. Poll-thread only and the
     * sole writer of {@link #applied} (besides {@link #stop()}). A still-configured binding whose
     * guest IP is momentarily unresolvable keeps its old entry and is switched atomically once
     * resolvable, so hot-editing guest_ip/NIC never drops a working forward.
     *
     * @return whether any rule is still configured-but-unresolvable (keep polling).
     */
    private boolean reconcile() {
        if (!running || networkStore == null) return false;
        var rules = parseRules();
        // Resolve outside the applied lock; DHCP / neighbor lookups are slow.
        var desired = new ArrayList<Applied>();
        boolean hasUnresolved = false;
        for (var rule : rules) {
            var target = resolveTarget(rule);
            if (target == null) {
                hasUnresolved = true;
                continue;
            }
            desired.add(new Applied(target.bridge, target.ip, rule.protocol,
                rule.hostIp, rule.hostPort, rule.guestPort));
        }
        synchronized (applied) {
            applied.removeIf(a -> {
                for (var d : desired)
                    if (sameForward(a, d)) return false;
                if (hostBindingConfigured(a, rules)) return false;
                networkStore.firewall.removeForward(
                    a.bridge, a.guestIp, a.protocol, a.hostIp, a.hostPort, a.guestPort);
                Log.i(TAG, fmt("VM %s: removed forward %s :%d -> %s:%d",
                    vm.getName(), a.protocol, a.hostPort, a.guestIp, a.guestPort));
                return true;
            });
            for (var d : desired) {
                boolean exists = false;
                for (var a : applied)
                    if (sameForward(a, d)) {
                        exists = true;
                        break;
                    }
                if (exists) continue;
                applied.removeIf(a -> {
                    if (!sameHostBinding(a, d)) return false;
                    networkStore.firewall.removeForward(
                        a.bridge, a.guestIp, a.protocol, a.hostIp, a.hostPort, a.guestPort);
                    Log.i(TAG, fmt("VM %s: switched forward %s :%d off %s:%d",
                        vm.getName(), a.protocol, a.hostPort, a.guestIp, a.guestPort));
                    return true;
                });
                boolean ok = networkStore.firewall.applyForward(
                    d.bridge, d.guestIp, d.protocol, d.hostIp, d.hostPort, d.guestPort);
                if (ok) {
                    applied.add(d);
                    Log.i(TAG, fmt("VM %s: forward %s :%d -> %s:%d",
                        vm.getName(), d.protocol, d.hostPort, d.guestIp, d.guestPort));
                } else {
                    Log.w(TAG, fmt("VM %s: failed to apply forward %s :%d",
                        vm.getName(), d.protocol, d.hostPort));
                }
            }
        }
        return hasUnresolved;
    }

    private static boolean sameForward(@NonNull Applied a, @NonNull Applied b) {
        return a.protocol.equals(b.protocol)
            && a.hostPort == b.hostPort
            && a.guestPort == b.guestPort
            && eq(a.hostIp, b.hostIp)
            && eq(a.guestIp, b.guestIp)
            && eq(a.bridge, b.bridge);
    }

    private static boolean sameHostBinding(@NonNull Applied a, @NonNull Applied b) {
        return a.protocol.equals(b.protocol) && a.hostPort == b.hostPort && eq(a.hostIp, b.hostIp);
    }

    private static boolean hostBindingConfigured(@NonNull Applied a, @NonNull List<Rule> rules) {
        for (var r : rules)
            if (a.protocol.equals(r.protocol) && a.hostPort == r.hostPort && eq(a.hostIp, r.hostIp))
                return true;
        return false;
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Reconcile/wait loop; stays alive the whole RUNNING period so late rule changes are still reconciled. */
    private void loop() {
        try {
            int attempts = 0;
            synchronized (this) {
                while (running && vm.getState() == VMState.RUNNING) {
                    boolean hasUnresolved = reconcile();
                    if (!running) break;
                    if (hasUnresolved && attempts < RESOLVE_MAX_ATTEMPTS) {
                        attempts++;
                        wait(RESOLVE_INTERVAL_MS);
                    } else {
                        if (hasUnresolved)
                            Log.w(TAG, fmt("VM %s: gave up auto-resolving guest IP for some port"
                                    + " forwards after %d attempts; will retry on next config change",
                                vm.getName(), attempts));
                        attempts = 0;
                        wait();
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void removeAll() {
        synchronized (applied) {
            if (networkStore != null)
                for (var a : applied)
                    networkStore.firewall.removeForward(
                        a.bridge, a.guestIp, a.protocol, a.hostIp, a.hostPort, a.guestPort);
            applied.clear();
        }
    }

    @NonNull
    private List<Rule> parseRules() {
        var list = new ArrayList<Rule>();
        var pf = vm.item.opt("port_forwards", null);
        if (pf == null || !pf.is(DataItem.Type.ARRAY)) return list;
        var seen = new HashSet<String>();
        for (var iter : pf) {
            var r = iter.getValue();
            if (!r.is(DataItem.Type.OBJECT)) continue;
            if (!r.optBoolean("enabled", true)) continue;
            var protocol = r.optString("protocol", "tcp");
            if (protocol == null || protocol.isEmpty()) protocol = "tcp";
            protocol = protocol.toLowerCase(Locale.ROOT);
            if (!protocol.equals("tcp") && !protocol.equals("udp")) continue;
            long hostPort = r.optLong("host_port", 0);
            if (hostPort <= 0 || hostPort > 65535) continue;
            long guestPort = r.optLong("guest_port", 0);
            if (guestPort <= 0 || guestPort > 65535) guestPort = hostPort;
            var hostIp = r.optString("host_ip", "");
            if (hostIp == null) hostIp = "";
            var key = fmt("%s|%s|%d", protocol, hostIp, hostPort);
            if (!seen.add(key)) {
                Log.w(TAG, fmt("VM %s: duplicate port forward %s, skipping", vm.getName(), key));
                continue;
            }
            var rule = new Rule();
            rule.protocol = protocol;
            rule.hostIp = hostIp;
            rule.hostPort = (int) hostPort;
            rule.guestPort = (int) guestPort;
            rule.networkId = r.optString("network_id", "");
            rule.fixedGuestIp = r.optString("guest_ip", "");
            list.add(rule);
        }
        return list;
    }

    @Nullable
    private Target resolveTarget(@NonNull Rule rule) {
        var net = findNetwork(rule.networkId);
        if (net == null) return null;
        var netId = net.optString("network_id", "");
        if (netId == null || netId.isEmpty()) return null;
        var netInst = networkStore.findById(netId);
        if (netInst == null) return null;
        var bridge = netInst.item.optString("bridge_name", "");
        if (bridge == null || bridge.isEmpty()) return null;
        if (rule.fixedGuestIp != null && !rule.fixedGuestIp.isEmpty())
            return new Target(bridge, rule.fixedGuestIp);
        var mac = net.optString("mac_address", "");
        if (mac == null || mac.isEmpty()) return null;
        var ip = resolveGuestIpByMac(netInst, bridge, mac);
        if (ip == null) return null;
        return new Target(bridge, ip);
    }

    @Nullable
    private String resolveGuestIpByMac(
        @NonNull NetworkInstance netInst, @NonNull String bridge, @NonNull String mac) {
        var macLower = mac.toLowerCase(Locale.ROOT);
        var ip = matchMac(networkStore.backend.listDhcpLeases(bridge), macLower, "ip", "mac");
        if (ip != null) return ip;
        return matchMac(netInst.listNeighbors(), macLower, "dst", "lladdr");
    }

    @Nullable
    private static String matchMac(
        @Nullable JSONArray arr, @NonNull String macLower,
        @NonNull String ipKey, @NonNull String macKey) {
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            var o = arr.optJSONObject(i);
            if (o == null) continue;
            var m = o.optString(macKey, "");
            if (!m.isEmpty() && m.toLowerCase(Locale.ROOT).equals(macLower)) {
                var ip = o.optString(ipKey, "");
                if (!ip.isEmpty()) return ip;
            }
        }
        return null;
    }

    @Nullable
    private DataItem findNetwork(@Nullable String networkId) {
        var nets = vm.item.opt("networks", null);
        if (nets == null || !nets.is(DataItem.Type.ARRAY)) return null;
        DataItem first = null;
        for (var iter : nets) {
            var net = iter.getValue();
            var nid = net.optString("network_id", "");
            if (nid == null || nid.isEmpty()) continue;
            if (first == null) first = net;
            if (networkId == null || networkId.isEmpty()) return net;
            if (networkId.equals(nid)) return net;
        }
        return first;
    }

    private static final class Applied {
        final String bridge;
        final String guestIp;
        final String protocol;
        final String hostIp;
        final int hostPort;
        final int guestPort;

        Applied(String bridge, String guestIp, String protocol,
                String hostIp, int hostPort, int guestPort) {
            this.bridge = bridge;
            this.guestIp = guestIp;
            this.protocol = protocol;
            this.hostIp = hostIp;
            this.hostPort = hostPort;
            this.guestPort = guestPort;
        }
    }

    private static final class Rule {
        String protocol;
        String hostIp;
        int hostPort;
        int guestPort;
        String networkId;
        String fixedGuestIp;
    }

    private static final class Target {
        final String bridge;
        final String ip;

        Target(String bridge, String ip) {
            this.bridge = bridge;
            this.ip = ip;
        }
    }
}
