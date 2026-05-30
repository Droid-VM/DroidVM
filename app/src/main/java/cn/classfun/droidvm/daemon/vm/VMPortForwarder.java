package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleep;

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
 * 管理单个 VM 的端口转发（host -> guest）iptables DNAT 规则。
 * VM 进入 RUNNING 后启动：轮询 DHCP 租约/邻居表，按网卡 MAC 解析 guest IP，
 * 然后下发规则；VM 退出时撤销全部已下发的规则。
 *
 * <p>规则来源于 VM 配置的 {@code port_forwards} 数组，每项字段：
 * <ul>
 *   <li>{@code protocol}: tcp | udp（默认 tcp）</li>
 *   <li>{@code host_port}: 宿主机监听端口（必填）</li>
 *   <li>{@code guest_port}: guest 目标端口（默认与 host_port 相同）</li>
 *   <li>{@code host_ip}: 可选，仅转发发往该宿主地址的流量；留空=全部</li>
 *   <li>{@code network_id}: 可选，多网卡时指定走哪个网卡；留空=首个网卡</li>
 *   <li>{@code guest_ip}: 可选，手动指定 guest IP，跳过自动发现</li>
 *   <li>{@code enabled}: 可选，默认 true</li>
 * </ul>
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

    /**
     * 运行时热同步当前配置：
     * <ul>
     *   <li>已在转发（VM 启动时已有规则）→ {@link #reapply()} 做增量 diff；</li>
     *   <li>此前因无规则未启动 → {@link #start()} 按新配置启动轮询应用。</li>
     * </ul>
     * 供 {@link VMInstance#applyPortForwards} 在运行时改规则后调用。
     */
    synchronized void sync() {
        if (running) reapply();
        else start();
    }

    /**
     * 运行时热更新：基于 VM 当前 {@code port_forwards} 配置对已应用规则做增量 diff——
     * 移除不再需要的、应用新增的。仅在 VM 运行（已 {@link #start()}）时有效。
     * 新增规则要求 guest IP 当前可解析（运行中 VM 一般已联网，故可解析）。
     */
    private synchronized void reapply() {
        if (!running || networkStore == null) return;
        var rules = parseRules();
        // 锁外解析目标，避免持 applied 锁期间做 DHCP/邻居表查询
        var desired = new ArrayList<Applied>();
        for (var rule : rules) {
            var target = resolveTarget(rule);
            if (target == null) {
                Log.w(TAG, fmt("VM %s: reapply skipped unresolved %s :%d (guest IP not ready)",
                    vm.getName(), rule.protocol, rule.hostPort));
                continue;
            }
            desired.add(new Applied(target.bridge, target.ip, rule.protocol,
                rule.hostIp, rule.hostPort, rule.guestPort));
        }
        synchronized (applied) {
            applied.removeIf(a -> {
                boolean keep = false;
                for (var d : desired)
                    if (sameForward(a, d)) {
                        keep = true;
                        break;
                    }
                if (!keep) {
                    networkStore.firewall.removeForward(
                        a.bridge, a.guestIp, a.protocol, a.hostIp, a.hostPort, a.guestPort);
                    Log.i(TAG, fmt("VM %s: reapply removed %s :%d -> %s:%d",
                        vm.getName(), a.protocol, a.hostPort, a.guestIp, a.guestPort));
                }
                return !keep;
            });
            for (var d : desired) {
                boolean exists = false;
                for (var a : applied)
                    if (sameForward(a, d)) {
                        exists = true;
                        break;
                    }
                if (exists) continue;
                boolean ok = networkStore.firewall.applyForward(
                    d.bridge, d.guestIp, d.protocol, d.hostIp, d.hostPort, d.guestPort);
                if (ok) {
                    applied.add(d);
                    Log.i(TAG, fmt("VM %s: reapply added %s :%d -> %s:%d",
                        vm.getName(), d.protocol, d.hostPort, d.guestIp, d.guestPort));
                } else {
                    Log.w(TAG, fmt("VM %s: reapply failed to apply %s :%d",
                        vm.getName(), d.protocol, d.hostPort));
                }
            }
        }
    }

    private static boolean sameForward(@NonNull Applied a, @NonNull Applied b) {
        return a.protocol.equals(b.protocol)
            && a.hostPort == b.hostPort
            && a.guestPort == b.guestPort
            && eq(a.hostIp, b.hostIp)
            && eq(a.guestIp, b.guestIp)
            && eq(a.bridge, b.bridge);
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void loop() {
        var rules = parseRules();
        if (rules.isEmpty()) return;
        int attempts = 0;
        while (running && vm.getState() == VMState.RUNNING) {
            boolean allDone = true;
            for (var rule : rules) {
                if (rule.done) continue;
                if (!running) return;
                var target = resolveTarget(rule);
                if (target == null) {
                    allDone = false;
                    continue;
                }
                boolean ok = networkStore.firewall.applyForward(
                    target.bridge, target.ip, rule.protocol,
                    rule.hostIp, rule.hostPort, rule.guestPort);
                rule.done = true;
                if (!ok) {
                    Log.w(TAG, fmt("VM %s: failed to apply forward %s :%d -> %s:%d",
                        vm.getName(), rule.protocol, rule.hostPort, target.ip, rule.guestPort));
                    continue;
                }
                // 与 stop()/removeAll() 通过 applied 锁互斥，避免 stop 后仍残留规则
                synchronized (applied) {
                    if (!running) {
                        networkStore.firewall.removeForward(
                            target.bridge, target.ip, rule.protocol,
                            rule.hostIp, rule.hostPort, rule.guestPort);
                    } else {
                        applied.add(new Applied(target.bridge, target.ip, rule.protocol,
                            rule.hostIp, rule.hostPort, rule.guestPort));
                        Log.i(TAG, fmt("VM %s: forward %s :%d -> %s:%d",
                            vm.getName(), rule.protocol, rule.hostPort, target.ip, rule.guestPort));
                    }
                }
            }
            if (allDone) break;
            if (++attempts >= RESOLVE_MAX_ATTEMPTS) {
                Log.w(TAG, fmt("VM %s: gave up resolving guest IP for some port forwards after %d attempts",
                    vm.getName(), attempts));
                break;
            }
            threadSleep(RESOLVE_INTERVAL_MS);
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
            // 同一 VM 内按 (protocol, host_ip, host_port) 去重，避免重复 DNAT
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
        // 1. 优先用 dnsmasq DHCP 租约
        var ip = matchMac(networkStore.backend.listDhcpLeases(bridge), macLower, "ip", "mac");
        if (ip != null) return ip;
        // 2. 回退到 ARP 邻居表（适用于静态 IP 且已通信过的 guest）
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
        boolean done = false;
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
