package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The single entry point for every netlink operation the daemon performs.
 *
 * <p>The daemon used to shell out to the system {@code ip}/{@code bridge}
 * (iproute2), which vary per OEM — some are too old to even emit
 * {@code IFA_RT_PRIORITY} in {@code -j} JSON, the field pbridge tags its
 * offload-proxy addresses with. {@code netbox} is our bundled, static Rust tool
 * that does the same operations straight over rtnetlink with a fixed JSON
 * schema, so behaviour is identical on every device. All argv building, binary
 * resolution and JSON parsing live here; callers use the typed methods only.
 *
 * <p>Mutations return {@code true} on success (the tool's exit code); queries
 * return the tool's parsed JSON ({@link JSONArray}, empty on any failure).
 */
final class Netbox {
    private static final String TAG = "Netbox";

    private Netbox() {
    }

    // ---------------------------------------------------------- link mutations

    static boolean linkAddBridge(@NonNull String name) {
        return run("link-add-bridge", name);
    }

    static boolean linkAddVlan(@NonNull String parent, @NonNull String name, int vid) {
        return run("link-add-vlan", "--link", parent, "--name", name, "--id", String.valueOf(vid));
    }

    static boolean linkAddTap(@NonNull String name) {
        return run("link-add-tap", name);
    }

    static boolean linkDel(@NonNull String name) {
        return run("link-del", name);
    }

    static boolean linkSetState(@NonNull String name, boolean up) {
        return run("link-set-state", name, up ? "up" : "down");
    }

    static boolean linkSetMaster(@NonNull String iface, @NonNull String master) {
        return run("link-set-master", iface, master);
    }

    static boolean linkSetNomaster(@NonNull String iface) {
        return run("link-set-nomaster", iface);
    }

    static boolean linkSetMac(@NonNull String dev, @NonNull String mac) {
        return run("link-set-mac", dev, mac);
    }

    static boolean linkSetStp(@NonNull String bridge, boolean on) {
        return run("link-set-stp", bridge, on ? "on" : "off");
    }

    static boolean linkSetIsolated(@NonNull String dev, boolean on) {
        return run("link-set-isolated", dev, on ? "on" : "off");
    }

    static boolean linkSetLocked(@NonNull String dev, boolean on) {
        return run("link-set-locked", dev, on ? "on" : "off");
    }

    // ---------------------------------------------------------- addr mutations

    static boolean addrAdd(@NonNull String dev, @NonNull String cidr) {
        return run("addr-add", dev, cidr);
    }

    static boolean addrDel(@NonNull String dev, @NonNull String cidr) {
        return run("addr-del", dev, cidr);
    }

    // ------------------------------------------------------- route/rule/fdb

    static boolean routeAdd(@NonNull String dev, @NonNull String dst, @NonNull String table, boolean v6) {
        return run(v6Args("route-add", v6, "--dev", dev, "--dst", dst, "--table", table));
    }

    static boolean ruleAdd(@NonNull String iif, @NonNull String table, boolean v6) {
        return run(v6Args("rule-add", v6, "--iif", iif, "--table", table));
    }

    static boolean ruleDel(@NonNull String iif, @NonNull String table, boolean v6) {
        return run(v6Args("rule-del", v6, "--iif", iif, "--table", table));
    }

    static boolean fdbAdd(@NonNull String mac, @NonNull String dev) {
        return run("fdb-add", mac, dev);
    }

    // ---------------------------------------------------------------- queries

    /** Addresses as {@code [{ifname,family,local,prefixlen,scope,metric,noprefixroute}]}. */
    @NonNull
    static JSONArray addrList(@Nullable String dev, @Nullable Long metric) {
        var args = new ArrayList<String>();
        args.add("addr-list");
        if (dev != null) {
            args.add("--dev");
            args.add(dev);
        }
        if (metric != null) {
            args.add("--metric");
            args.add(String.valueOf(metric));
        }
        return query(args);
    }

    /** Links as {@code [{ifname,address,operstate,master,mtu,kind}]}. */
    @NonNull
    static JSONArray linkList(@Nullable String master, boolean bridgeOnly) {
        var args = new ArrayList<String>();
        args.add("link-list");
        if (master != null) {
            args.add("--master");
            args.add(master);
        }
        if (bridgeOnly) args.add("--type-bridge");
        return query(args);
    }

    /** Neighbours of one device as {@code [{dst,lladdr,dev,state[]}]}. */
    @NonNull
    static JSONArray neighList(@NonNull String dev) {
        return query(List.of("neigh-list", dev));
    }

    /** Policy rules as {@code [{priority,iif,table,fwmark,fwmask,detached}]}. */
    @NonNull
    static JSONArray ruleList(boolean v6) {
        return query(v6 ? List.of("rule-list", "--v6") : List.of("rule-list"));
    }

    // -------------------------------------------------------------- host IPs

    /**
     * The phone's own reachable IPv4 addresses (one-shot). netbox applies the
     * whole policy itself: only addresses on interfaces whose name starts with
     * one of {@code prefixes} (the Wi-Fi/cellular/VPN/ethernet/tethering
     * allowlist), with bridge devices and {@code excludeMetric}-tagged
     * (pbridge offload) addresses dropped. Empty on any failure.
     */
    @NonNull
    static Set<String> hostIpv4(@NonNull List<String> prefixes, @Nullable Long excludeMetric) {
        var cmd = new ArrayList<String>();
        cmd.add(getAssetBinaryPath("netbox"));
        cmd.addAll(hostIpArgs("host-ips", prefixes, excludeMetric));
        var result = runListQuiet(cmd);
        if (!result.isSuccess()) {
            Log.w(TAG, fmt("netbox host-ips failed (exit %d): %s",
                result.getCode(), result.getErrString()));
            return Set.of();
        }
        return parseHostIps(result.getOutString());
    }

    /**
     * Streams host-IPv4 changes: starts {@code netbox monitor-addr} and calls
     * {@code onChange} with the new set on every change (the same policy as
     * {@link #hostIpv4}). netbox emits only on an actual change, so the
     * callback fires only when the set really moved. Returns the supervised
     * process; the caller owns its lifecycle (and should {@code stop()} it).
     */
    @NonNull
    static ManagedProcess startHostIpMonitor(
        @NonNull List<String> prefixes, @Nullable Long excludeMetric,
        @NonNull Consumer<Set<String>> onChange
    ) {
        var cmd = new ArrayList<String>();
        cmd.add(getAssetBinaryPath("netbox"));
        cmd.addAll(hostIpArgs("monitor-addr", prefixes, excludeMetric));
        var proc = new ManagedProcess("netbox", "hostip-mon");
        proc.setLineListener(line -> {
            var trimmed = line.trim();
            // skip any non-JSON diagnostics merged from stderr
            if (trimmed.isEmpty() || trimmed.charAt(0) != '{') return;
            onChange.accept(parseHostIps(trimmed));
        });
        proc.start(cmd);
        return proc;
    }

    @NonNull
    private static List<String> hostIpArgs(
        @NonNull String sub, @NonNull List<String> prefixes, @Nullable Long excludeMetric
    ) {
        var args = new ArrayList<String>();
        args.add(sub);
        for (var p : prefixes) {
            args.add("--iface");
            args.add(p);
        }
        if (excludeMetric != null) {
            args.add("--exclude-metric");
            args.add(String.valueOf(excludeMetric));
        }
        return args;
    }

    /** Parse one {@code {"v4":[...]}} line into a set; empty on any failure. */
    @NonNull
    private static Set<String> parseHostIps(@NonNull String json) {
        var out = new LinkedHashSet<String>();
        try {
            var arr = new JSONObject(json).optJSONArray("v4");
            if (arr != null)
                for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to parse netbox host-ips output: %s", json), e);
        }
        return out;
    }

    // ----------------------------------------------------------------- plumbing

    @NonNull
    private static String[] v6Args(@NonNull String sub, boolean v6, @NonNull String... rest) {
        var args = new ArrayList<String>();
        args.add(sub);
        java.util.Collections.addAll(args, rest);
        if (v6) args.add("--v6");
        return args.toArray(new String[0]);
    }

    private static boolean run(@NonNull String... args) {
        var cmd = new ArrayList<String>(args.length + 1);
        cmd.add(getAssetBinaryPath("netbox"));
        java.util.Collections.addAll(cmd, args);
        var result = runListQuiet(cmd);
        if (!result.isSuccess()) {
            Log.w(TAG, fmt("netbox %s failed (exit %d): %s",
                args[0], result.getCode(), result.getErrString()));
        }
        return result.isSuccess();
    }

    @NonNull
    private static JSONArray query(@NonNull List<String> args) {
        var cmd = new ArrayList<String>(args.size() + 1);
        cmd.add(getAssetBinaryPath("netbox"));
        cmd.addAll(args);
        var result = runListQuiet(cmd);
        if (!result.isSuccess()) {
            Log.w(TAG, fmt("netbox %s failed (exit %d): %s",
                args.get(0), result.getCode(), result.getErrString()));
            return new JSONArray();
        }
        try {
            return new JSONArray(result.getOutString());
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to parse netbox %s output", args.get(0)), e);
            return new JSONArray();
        }
    }
}
