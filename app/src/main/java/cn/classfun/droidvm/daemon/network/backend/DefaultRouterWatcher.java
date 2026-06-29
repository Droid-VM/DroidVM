package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.lib.Constants;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.NetworkState;
import cn.classfun.droidvm.lib.store.network.UplinkMode;

public final class DefaultRouterWatcher {
    private static final String TAG = "DefaultRouterWatcher";
    private static final long POLL_INTERVAL_SEC = 5;
    /**
     * FRA_PRIORITY of the first "iif {bridge} lookup {table}" rule; rule i in a
     * bridge's chain gets {@code RULE_PRIORITY_BASE + i}. Sits above the local
     * table (priority 0) and below netd's lowest rule (10000), so a guest packet
     * is matched by the local table first, then our chain, before netd's
     * "from all" fallbacks. Leaves headroom for far more tables than any device
     * has networks.
     */
    private static final int RULE_PRIORITY_BASE = 9000;
    /**
     * Interface-name prefixes whose IPv4 addresses count as the phone's own
     * reachable IPs for port-forward DNAT scoping: Wi-Fi, cellular, VPN,
     * ethernet, and hotspot/USB/BT tethering. Passed to netbox, which also
     * drops bridge devices and pbridge-offload addresses. Cellular names other
     * than rmnet_data (ccmni, pdp_ip...) are intentionally not matched -- same
     * assumption the iptables EXT_IFACES list already makes.
     */
    private static final List<String> HOST_IFACE_PREFIXES = List.of(
        "wlan", "rmnet_data", "tun", "eth",
        "ap", "swlan", "softap", "rndis", "usb", "bt-pan"
    );
    private final ServerContext context;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> hostIpListeners = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler;
    private List<String> lastTables4 = List.of();
    private List<String> lastTables6 = List.of();
    /** Phone's own IPv4 addresses; the netbox host-ips policy decides the set. */
    private volatile Set<String> hostIpv4 = Set.of();
    /** netbox monitor-addr: pushes host-IP changes the instant they happen. */
    private ManagedProcess hostIpMonitor = null;
    /** Gates monitor (re)spawn so a post-stop tick can't resurrect it. */
    private volatile boolean monitorEnabled = false;

    public DefaultRouterWatcher(@NonNull ServerContext context) {
        this.context = context;
    }

    /**
     * The ordered routing tables a default-network app's locally-generated
     * traffic would traverse, per family -- the egress half of guest routing.
     * Forwarded guest packets carry no fwmark, so they never match netd's
     * uid/netId-keyed "iif lo" rules; we mirror the tables those rules select
     * into "iif {bridge}" rules instead (see {@link #reconcileRules}).
     *
     * <p>A default app's packet has fwmark 0 (no explicitly selected network,
     * not VPN-protected), so it is selected by exactly the "iif lo" lookup
     * rules whose fwmark is unset/0:
     * <ul>
     *   <li>the secure-VPN rule ("from all fwmark 0x0/0x20000 iif lo ... lookup
     *       {vpn}", priority 13000) -> the VPN table holding a split tunnel's
     *       routes (e.g. 10.0.0.0/8);</li>
     *   <li>the default-network rule ("from all fwmark 0x0/0xffff iif lo lookup
     *       {default}") -> the underlying network's table (e.g. wlan0).</li>
     * </ul>
     * netId explicit/implicit rules carry a nonzero fwmark and are skipped.
     * Returned in netd priority order so a split-tunnel VPN's table is consulted
     * before the default one: a guest packet to 10.x matches the VPN table;
     * anything else finds no route there and falls through to the default table
     * (kernel policy-rule fall-through). With no VPN the list is just the single
     * default table; a full-tunnel VPN's table has a default route, so the trailing
     * default table is simply never reached.
     *
     * <p>Matching is on what netbox exposes (priority, iif, table, fwmark,
     * fwmask -- not oif/uidrange), so the filter must single out the fwmark
     * selection rules:
     * <ul>
     *   <li>{@code lookup} -- it routes to a table (not unreachable/blackhole);</li>
     *   <li>{@code iif == "lo"} -- locally-generated selection rules, not the
     *       "from all" legacy/local-network rules that forwarded packets already
     *       traverse;</li>
     *   <li>{@code fwmark} unset/0 -- a default app's mark (the kernel omits
     *       FRA_FWMARK when the mark is 0); netId explicit/implicit rules carry a
     *       nonzero mark and are skipped;</li>
     *   <li>{@code fwmask} present -- this is the key discriminator. A real
     *       fwmark selector always emits FRA_FWMASK (the kernel sends it whenever
     *       the mask is nonzero, e.g. 0x20000/0xffff), even when the mark value
     *       is 0. The "iif lo oif &lt;iface&gt; ... lookup" output-interface rules
     *       (priorities 11000/17000, incl. dummy0 blackholes) carry no mark or
     *       mask, so their fwmask is null and they are excluded -- without this
     *       check those oif rules would be picked up (their oif is invisible to
     *       us) and wrongly rank wlan0/dummy0 ahead of the VPN table.</li>
     * </ul>
     */
    @NonNull
    private List<String> currentLocalAppTables(boolean ipv6) {
        // ruleList is in kernel order = ascending priority, so collecting in
        // iteration order preserves netd's precedence (secure-VPN before default).
        var tables = new LinkedHashSet<String>();
        var rows = Netbox.ruleList(ipv6);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            if (!r.optBoolean("lookup", false)) continue;
            if (!"lo".equals(r.optString("iif", ""))) continue;
            if (r.isNull("fwmask")) continue;
            if (!r.isNull("fwmark") && r.optInt("fwmark", 0) != 0) continue;
            tables.add(String.valueOf(r.optInt("table", 0)));
        }
        return new ArrayList<>(tables);
    }

    private void ruleDel(boolean ipv6, @NonNull String dev, @NonNull String table, int priority) {
        Netbox.ruleDel(dev, table, priority, ipv6);
    }

    private void ruleAdd(boolean ipv6, @NonNull String dev, @NonNull String table, int priority) {
        Netbox.ruleAdd(dev, table, priority, ipv6);
    }

    /**
     * Reconciles one family's per-bridge "iif {bridge} priority {p} lookup
     * {table}" rule chains against {@code tables} (the egress half of guest
     * routing: forwarded packets carry no fwmark and Android's netd rules end
     * in "from all unreachable", so each bridge needs explicit rules into the
     * tables a local app would use). Each running Linux-L3 bridge gets the
     * whole ordered {@code tables} chain so split-tunnel works: the VPN table
     * is tried before the default one and the kernel falls through when it has
     * no matching route -- see {@link #currentLocalAppTables}.
     *
     * <p>Order matters (a packet to 10.x must hit the VPN table before the
     * default table's 0.0.0.0/0). We pin it with explicit FRA_PRIORITY rather
     * than insertion order: rule i gets priority {@code RULE_PRIORITY_BASE + i}
     * (ascending = tables[0] first). Insertion order is unreliable -- the kernel
     * matches same-priority rules FIFO or LIFO depending on version (observed
     * LIFO on-device, which silently reversed the chain). The band sits below
     * netd's lowest rule (10000) and above the local table (0), so a guest
     * packet hits local first, then our chain.
     *
     * <p>Declarative per chain: a bridge whose installed rules (each as a
     * "priority table" spec, sorted by priority) exactly equal the desired
     * chain is left untouched; any other bridge -- wrong tables/priorities
     * (incl. the pre-priority builds' priority-0 rules), [detached] from a
     * recreated bridge, or no longer desired -- has all its rules deleted (each
     * by its own priority) and, if still desired, the full chain re-added. An
     * empty {@code tables} (no default network, or stop()) desires no bridge,
     * so every leftover rule is swept.
     */
    private void reconcileRules(boolean ipv6, @NonNull List<String> tables) {
        var desiredDevs = new LinkedHashSet<String>();
        var bridges = new ArrayList<String>();
        context.getNetworks().forEach((uuid, inst) -> {
            var br = inst.getBridgeName();
            if (br != null && !br.isEmpty()) bridges.add(br);
            // Only a running Linux-bridge L3 network has a real kernel bridge
            // that forwarded guest traffic needs "iif <bridge> lookup <table>"
            // rules for. gvisor is a userspace data path (gvswitch + AF_XDP)
            // with no kernel device for its bridge name, so a rule on it would
            // sit [detached] and flap every tick; a stopped network has no
            // bridge either. Whitelist the one case that needs rules -- any
            // other backend is excluded by default. Both still go into
            // `bridges` above so their leftover rules are swept here.
            if (!tables.isEmpty()
                && inst.getState() == NetworkState.RUNNING
                && inst.getBridgeType() == BridgeType.LINUX
                && inst.getUplinkMode() == UplinkMode.L3)
                desiredDevs.addAll(inst.getL3Devices());
        });

        // Desired chain as "priority table" specs in ascending priority; the
        // same list for every desired bridge. tables[0] (the VPN table) gets the
        // lowest priority so it is evaluated first.
        var desired = new ArrayList<String>();
        for (int i = 0; i < tables.size(); i++)
            desired.add((RULE_PRIORITY_BASE + i) + " " + tables.get(i));

        // Group our installed rules per device as "priority table" specs. Ours
        // are the plain rules: an iif owned by a bridge, a lookup action, no
        // fwmark/mask (netd's are all marked or oif/uid scoped). Sort each
        // device's specs by priority so the comparison is order-canonical.
        var installed = new LinkedHashMap<String, List<String>>();
        var detached = new HashSet<String>();
        var rows = Netbox.ruleList(ipv6);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            var dev = r.optString("iif", "");
            if (dev.isEmpty()) continue;
            if (!r.optBoolean("lookup", false)) continue;
            if (!r.isNull("fwmark") || !r.isNull("fwmask")) continue;
            boolean ours = false;
            for (var br : bridges)
                if (dev.startsWith(br)) { ours = true; break; }
            if (!ours) continue;
            installed.computeIfAbsent(dev, k -> new ArrayList<>())
                .add(r.optInt("priority", 0) + " " + r.optInt("table", 0));
            if (r.optBoolean("detached", false)) detached.add(dev);
        }
        installed.values().forEach(specs -> specs.sort(Comparator.comparingInt(
            s -> Integer.parseInt(s.substring(0, s.indexOf(' '))))));

        // A device is already correct iff it is still desired, has no detached
        // rule, and its installed specs equal the desired chain (same tables AND
        // priorities). Everything else is torn down -- each rule deleted by its
        // own priority -- and desired-but-incorrect devices are rebuilt below.
        var correct = new HashSet<String>();
        installed.forEach((dev, specs) -> {
            if (desiredDevs.contains(dev)
                && !detached.contains(dev)
                && specs.equals(desired)) {
                correct.add(dev);
                return;
            }
            Log.i(TAG, fmt("Removing stale chain: iif %s [%s] (v%d)",
                dev, specs, ipv6 ? 6 : 4));
            for (var spec : specs) {
                int sp = spec.indexOf(' ');
                ruleDel(ipv6, dev, spec.substring(sp + 1),
                    Integer.parseInt(spec.substring(0, sp)));
            }
        });

        for (var dev : desiredDevs) {
            if (correct.contains(dev)) continue;
            Log.i(TAG, fmt("Adding chain: iif %s [%s] (v%d)",
                dev, desired, ipv6 ? 6 : 4));
            for (int i = 0; i < tables.size(); i++)
                ruleAdd(ipv6, dev, tables.get(i), RULE_PRIORITY_BASE + i);
        }
    }

    private synchronized void runOnce() {
        ensureHostIpMonitor();
        updateHostIps();
        updateDefaultRouter();
    }

    private void updateDefaultRouter() {
        try {
            var new4 = currentLocalAppTables(false);
            var new6 = currentLocalAppTables(true);
            reconcileRules(false, new4);
            reconcileRules(true, new6);
            boolean changed = !new4.equals(lastTables4) || !new6.equals(lastTables6);
            if (changed)
                Log.i(TAG, fmt("Local-app route tables now v4=%s v6=%s", new4, new6));
            lastTables4 = new4;
            lastTables6 = new6;
            // notify only on appearance/change, not on loss: a reconnect to
            // the same network re-triggers because the loss emptied the state
            if (changed && (!new4.isEmpty() || !new6.isEmpty()))
                notifyChange();
        } catch (Exception e) {
            Log.w(TAG, "Failed to update default router", e);
            stop();
        }
    }

    private void notifyChange() {
        for (var listener : listeners) {
            try {
                listener.run();
            } catch (Exception e) {
                Log.w(TAG, "Default-network listener failed", e);
            }
        }
    }

    /**
     * Backstop poll of the phone's own IPv4 addresses for port-forward DNAT
     * scoping. The {@code netbox monitor-addr} stream is the primary, instant
     * source ({@link #onMonitorSet}); this tick covers a missed event or a dead
     * monitor. On a transient query failure the previous set is kept (returning
     * empty would flap every forward), so this never throws out of the tick.
     */
    private void updateHostIps() {
        try {
            applyHostIps(Netbox.hostIpv4(HOST_IFACE_PREFIXES, Constants.PBRIDGE_OFFLOAD_MAGIC));
        } catch (Exception e) {
            Log.w(TAG, "Failed to poll host IPv4 addresses", e);
        }
    }

    /** Diffs the fresh set against the cache and fires listeners if it moved. */
    private void applyHostIps(@NonNull Set<String> fresh) {
        if (fresh.equals(hostIpv4)) return;
        Log.i(TAG, fmt("Host IPv4 set changed %s -> %s", hostIpv4, fresh));
        hostIpv4 = Set.copyOf(fresh);
        for (var listener : hostIpListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                Log.w(TAG, "Host-IP listener failed", e);
            }
        }
    }

    /**
     * Receives a new host-IP set from the netbox monitor (on its process
     * thread). Hops onto the scheduler so all host-IP state mutates on one
     * thread, shared with the backstop tick; dropped if we're stopping.
     */
    private void onMonitorSet(@NonNull Set<String> fresh) {
        var s = scheduler;
        if (s == null) return;
        try {
            s.execute(() -> applyHostIps(fresh));
        } catch (RejectedExecutionException ignored) {
            // scheduler shutting down; the next start() reseeds
        }
    }

    /** Starts the monitor, or restarts it if it died (called each tick). */
    private void ensureHostIpMonitor() {
        if (!monitorEnabled) return;
        if (hostIpMonitor != null && hostIpMonitor.isRunning()) return;
        if (hostIpMonitor != null)
            Log.w(TAG, "host-IP monitor exited; restarting");
        hostIpMonitor = Netbox.startHostIpMonitor(
            HOST_IFACE_PREFIXES, Constants.PBRIDGE_OFFLOAD_MAGIC, this::onMonitorSet);
    }

    /** Current snapshot of the phone's own IPv4 addresses (immutable). */
    @NonNull
    public Set<String> getHostIpv4Addresses() {
        return hostIpv4;
    }

    /** Notified (on the watcher thread) when the host IPv4 set changes. */
    public void addHostIpListener(@NonNull Runnable listener) {
        hostIpListeners.add(listener);
    }

    public void removeHostIpListener(@NonNull Runnable listener) {
        hostIpListeners.remove(listener);
    }

    /** Notified (on the watcher thread) when the default network changes. */
    @SuppressWarnings("unused")
    public void addListener(@NonNull Runnable listener) {
        listeners.add(listener);
    }

    @SuppressWarnings("unused")
    public void removeListener(@NonNull Runnable listener) {
        listeners.remove(listener);
    }

    /** Installs the new network's rules immediately (the tick would lag 5s). */
    public synchronized void setForNewNetwork() {
        try {
            reconcileRules(false, currentLocalAppTables(false));
            reconcileRules(true, currentLocalAppTables(true));
        } catch (Exception e) {
            Log.w(TAG, "Failed to install rules for new network", e);
        }
    }

    public void start() {
        monitorEnabled = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, TAG);
            t.setDaemon(true);
            return t;
        });
        // the first tick (delay 0) spawns the monitor via ensureHostIpMonitor
        scheduler.scheduleWithFixedDelay(
            this::runOnce, 0, POLL_INTERVAL_SEC, TimeUnit.SECONDS
        );
    }

    public synchronized void stop() {
        monitorEnabled = false;
        if (hostIpMonitor != null) {
            hostIpMonitor.stop();
            hostIpMonitor = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        // sweep every rule of ours, both families
        try {
            reconcileRules(false, List.of());
            reconcileRules(true, List.of());
        } catch (Exception e) {
            Log.w(TAG, "Failed to sweep rules on stop", e);
        }
    }
}
