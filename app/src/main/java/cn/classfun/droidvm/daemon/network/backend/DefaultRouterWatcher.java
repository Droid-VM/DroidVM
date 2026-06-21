package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.lib.Constants;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.NetworkState;
import cn.classfun.droidvm.lib.store.network.UplinkMode;

public final class DefaultRouterWatcher {
    private static final String TAG = "DefaultRouterWatcher";
    private static final long POLL_INTERVAL_SEC = 5;
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
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Runnable> hostIpListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler;
    private String lastDefault4 = null;
    private String lastDefault6 = null;
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
     * The routing table of the current default network, per family: Android
     * keeps the default route out of main and selects it with the netd rule
     * "from all fwmark 0x0/0xffff iif lo lookup {table}".
     */
    @Nullable
    private String currentDefaultTable(boolean ipv6) {
        // netd selects the default network with "from all fwmark 0x0/0xffff
        // iif lo lookup {table}" (mark 0, mask 0xffff). The kernel omits
        // FRA_FWMARK when the mark is 0, so match on the 0xffff mask alone.
        var rows = Netbox.ruleList(ipv6);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            if (r.optInt("fwmark", 0) != 0) continue;
            if (r.optInt("fwmask", 0) != 0xffff) continue;
            return String.valueOf(r.optInt("table", 0));
        }
        return null;
    }

    private void ruleDel(boolean ipv6, @NonNull String dev, @NonNull String table) {
        Netbox.ruleDel(dev, table, ipv6);
    }

    private void ruleAdd(boolean ipv6, @NonNull String dev, @NonNull String table) {
        Netbox.ruleAdd(dev, table, ipv6);
    }

    /**
     * Reconciles one family's "iif {bridge} lookup {default-table}" rules
     * (the egress half of guest routing: forwarded packets carry no fwmark
     * and Android's netd rules end in "from all unreachable", so each
     * bridge needs an explicit rule into the default network's table).
     * Declarative per tick: deletes stale rules -- wrong table, duplicates
     * (added twice across restarts) and [detached] leftovers from a
     * recreated bridge -- and adds what is missing. Stale-rule deletion by
     * spec may remove either twin first; the next tick converges.
     */
    private void reconcileRules(boolean ipv6, @Nullable String table) {
        var desired = new LinkedHashSet<String>();
        var bridges = new ArrayList<String>();
        context.getNetworks().forEach((uuid, inst) -> {
            var br = inst.getBridgeName();
            if (br != null && !br.isEmpty()) bridges.add(br);
            // Only a running Linux-bridge L3 network has a real kernel bridge
            // that forwarded guest traffic needs an "iif <bridge> lookup
            // <table>" rule for. gvisor is a userspace data path (gvswitch +
            // AF_XDP) with no kernel device for its bridge name, so a rule on
            // it would sit [detached] and flap every tick; a stopped network
            // has no bridge either. Whitelist the one case that needs rules --
            // any other backend is excluded by default. Both still go into
            // `bridges` above so their leftover rules are swept here.
            if (table != null
                && inst.getState() == NetworkState.RUNNING
                && inst.getBridgeType() == BridgeType.LINUX
                && inst.getUplinkMode() == UplinkMode.L3)
                desired.addAll(inst.getL3Devices());
        });

        var satisfied = new LinkedHashSet<String>();
        var rows = Netbox.ruleList(ipv6);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            var dev = r.optString("iif", "");
            // only our "iif <bridge> lookup <table>" rules: skip rules with no
            // iif, and netd's marked rules (any fwmark/mask present)
            if (dev.isEmpty()) continue;
            if (!r.isNull("fwmark") || !r.isNull("fwmask")) continue;
            boolean ours = false;
            for (var br : bridges)
                if (dev.startsWith(br)) { ours = true; break; }
            if (!ours) continue;
            var tbl = String.valueOf(r.optInt("table", 0));
            boolean stale = r.optBoolean("detached", false)
                || table == null || !tbl.equals(table)
                || !desired.contains(dev) || satisfied.contains(dev);
            if (stale) {
                Log.i(TAG, fmt("Removing stale rule: iif %s lookup %s (v%d)",
                    dev, tbl, ipv6 ? 6 : 4));
                ruleDel(ipv6, dev, tbl);
            } else {
                satisfied.add(dev);
            }
        }
        for (var dev : desired) {
            if (satisfied.contains(dev)) continue;
            Log.i(TAG, fmt("Adding rule: iif %s lookup %s (v%d)",
                dev, table, ipv6 ? 6 : 4));
            ruleAdd(ipv6, dev, table);
        }
    }

    private synchronized void runOnce() {
        ensureHostIpMonitor();
        updateHostIps();
        updateDefaultRouter();
    }

    private void updateDefaultRouter() {
        try {
            var new4 = currentDefaultTable(false);
            var new6 = currentDefaultTable(true);
            reconcileRules(false, new4);
            reconcileRules(true, new6);
            boolean changed = !java.util.Objects.equals(new4, lastDefault4)
                || !java.util.Objects.equals(new6, lastDefault6);
            if (changed)
                Log.i(TAG, fmt("Default network now v4=%s v6=%s", new4, new6));
            lastDefault4 = new4;
            lastDefault6 = new6;
            // notify only on appearance/change, not on loss: a reconnect to
            // the same network re-triggers because the loss nulled the state
            if (changed && (new4 != null || new6 != null))
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
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
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
    public void addListener(@NonNull Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull Runnable listener) {
        listeners.remove(listener);
    }

    /** Installs the new network's rules immediately (the tick would lag 5s). */
    public synchronized void setForNewNetwork(@NonNull NetworkInstance inst) {
        try {
            reconcileRules(false, currentDefaultTable(false));
            reconcileRules(true, currentDefaultTable(true));
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
            reconcileRules(false, null);
            reconcileRules(true, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to sweep rules on stop", e);
        }
    }
}
