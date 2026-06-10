package cn.classfun.droidvm.daemon.network.backend.iptables;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.network.backend.FirewallHelper;
import cn.classfun.droidvm.lib.utils.RunUtils;

@SuppressWarnings("UnusedReturnValue")
public final class IptablesBackend extends FirewallHelper {
    private static final String TAG = "IptablesBackend";
    final ChainInfo FIL_NET_EXTNET_IN = new ChainInfo(this, "filter", null, "droidvm_ext_in");
    final ChainInfo FIL_NET_EXTNET_OUT = new ChainInfo(this, "filter", null, "droidvm_ext_out");
    final ChainInfo FIL_NET_INPUT = new ChainInfo(this, "filter", null, "droidvm_net_in");
    final ChainInfo FIL_IN = new ChainInfo(this, "filter", "INPUT", "droidvm_in");
    final ChainInfo FIL_OUT = new ChainInfo(this, "filter", "OUTPUT", "droidvm_out");
    final ChainInfo FIL_FWD = new ChainInfo(this, "filter", "FORWARD", "droidvm_fwd");
    final ChainInfo MAG_IN = new ChainInfo(this, "mangle", "INPUT", "droidvm_in");
    final ChainInfo MAG_OUT = new ChainInfo(this, "mangle", "OUTPUT", "droidvm_out");
    final ChainInfo MAG_FWD = new ChainInfo(this, "mangle", "FORWARD", "droidvm_fwd");
    final ChainInfo MAG_PST = new ChainInfo(this, "mangle", "POSTROUTING", "droidvm_pst");
    final ChainInfo MAG_PRE = new ChainInfo(this, "mangle", "PREROUTING", "droidvm_pre");
    final ChainInfo NAT_PST = new ChainInfo(this, "nat", "POSTROUTING", "droidvm_pst");
    final ChainInfo NAT_PRE = new ChainInfo(this, "nat", "PREROUTING", "droidvm_pre");
    final ChainInfo RAW_OUT = new ChainInfo(this, "raw", "OUTPUT", "droidvm_out");
    final ChainInfo RAW_PRE = new ChainInfo(this, "raw", "PREROUTING", "droidvm_pre");
    final ChainInfo[] DEFS = new ChainInfo[]{
        FIL_NET_EXTNET_IN,
        FIL_NET_EXTNET_OUT,
        FIL_NET_INPUT,
        FIL_IN,
        FIL_OUT,
        FIL_FWD,
        MAG_IN,
        MAG_OUT,
        MAG_FWD,
        MAG_PST,
        MAG_PRE,
        NAT_PST,
        NAT_PRE,
        RAW_OUT,
        RAW_PRE,
    };

    synchronized boolean iptables(@NonNull String... args) {
        int retry = 0;
        while (true) {
            var result = RunUtils.runList(args);
            result.printLog(TAG);
            boolean ret = result.isSuccess();
            if (ret) return true;
            if (!result.getErrString().contains("Can't lock")) return false;
            if (retry >= 5) return false;
            retry++;
        }
    }

    boolean iptablesCmd(@NonNull String table, @NonNull String... args) {
        var cmd = new ArrayList<String>();
        cmd.add("iptables");
        cmd.add("-t");
        cmd.add(table);
        cmd.addAll(Arrays.asList(args));
        return iptables(cmd.toArray(new String[0]));
    }

    @Override
    public boolean initialize() {
        Log.i(TAG, "Initializing iptables firewall chains");
        boolean ok = true;
        for (var def : DEFS)
            def.init(true);
        FIL_FWD.append("-m", "state", "--state", "RELATED,ESTABLISHED", "-j", "ACCEPT");
        FIL_NET_EXTNET_OUT.append("-o", "wlan+", "-j", "ACCEPT");
        FIL_NET_EXTNET_OUT.append("-o", "eth+", "-j", "ACCEPT");
        FIL_NET_EXTNET_OUT.append("-o", "rmnet_data+", "-j", "ACCEPT");
        FIL_NET_EXTNET_OUT.append("-j", "DROP");
        FIL_NET_EXTNET_IN.append("-i", "wlan+", "-j", "ACCEPT");
        FIL_NET_EXTNET_IN.append("-i", "eth+", "-j", "ACCEPT");
        FIL_NET_EXTNET_IN.append("-i", "rmnet_data+", "-j", "ACCEPT");
        FIL_NET_EXTNET_IN.append("-j", "DROP");
        enableIpForward();
        return ok;
    }

    @Override
    public boolean shutdown() {
        Log.i(TAG, "Shutting down iptables firewall chains");
        boolean ok = true;
        for (var def : DEFS)
            def.deinit();
        return ok;
    }

    public void initNetwork(NetworkInstance inst) {
        var net = new IptablesNetworkInstance(this, inst);
        net.init();
        net.initBasic();
    }

    public void deinitNetwork(NetworkInstance inst) {
        var net = new IptablesNetworkInstance(this, inst);
        net.deinit();
    }

    @Override
    public boolean applyForward(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @Nullable String hostIp, int hostPort, int guestPort
    ) {
        var pre = new ChainInfo(this, "nat", null, fmt("droidvm_pre_%s", bridge));
        var fwd = new ChainInfo(this, "filter", null, fmt("droidvm_fwd_%s", bridge));
        boolean ok = pre.append(dnatSpec(protocol, hostIp, hostPort, guestIp, guestPort));
        ok &= fwd.insert(fwdSpec(bridge, guestIp, protocol, guestPort));
        Log.i(TAG, fmt("Apply forward %s %s:%d -> %s:%d on %s (ok=%b)",
            protocol, isSpecificHost(hostIp) ? hostIp : "*",
            hostPort, guestIp, guestPort, bridge, ok));
        return ok;
    }

    @Override
    public boolean removeForward(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @Nullable String hostIp, int hostPort, int guestPort
    ) {
        var pre = new ChainInfo(this, "nat", null, fmt("droidvm_pre_%s", bridge));
        var fwd = new ChainInfo(this, "filter", null, fmt("droidvm_fwd_%s", bridge));
        boolean ok = pre.delete(dnatSpec(protocol, hostIp, hostPort, guestIp, guestPort));
        ok &= fwd.delete(fwdSpec(bridge, guestIp, protocol, guestPort));
        Log.i(TAG, fmt("Remove forward %s %s:%d -> %s:%d on %s (ok=%b)",
            protocol, isSpecificHost(hostIp) ? hostIp : "*",
            hostPort, guestIp, guestPort, bridge, ok));
        return ok;
    }

    @NonNull
    private static String[] dnatSpec(
        @NonNull String protocol, @Nullable String hostIp, int hostPort,
        @NonNull String guestIp, int guestPort
    ) {
        var spec = new ArrayList<String>();
        spec.add("-p");
        spec.add(protocol);
        if (isSpecificHost(hostIp)) {
            spec.add("-d");
            spec.add(hostIp);
        }
        spec.add("--dport");
        spec.add(String.valueOf(hostPort));
        spec.add("-j");
        spec.add("DNAT");
        spec.add("--to-destination");
        spec.add(fmt("%s:%d", guestIp, guestPort));
        return spec.toArray(new String[0]);
    }

    @NonNull
    private static String[] fwdSpec(
        @NonNull String bridge, @NonNull String guestIp,
        @NonNull String protocol, int guestPort
    ) {
        return new String[]{
            "-o", bridge, "-d", guestIp, "-p", protocol,
            "--dport", String.valueOf(guestPort), "-j", "ACCEPT"
        };
    }

    /** A wildcard host_ip (empty / 0.0.0.0[/0] / ::[/0]) must omit -d, or the DNAT never matches. */
    private static boolean isSpecificHost(@Nullable String hostIp) {
        return hostIp != null && !hostIp.isEmpty()
            && !hostIp.equals("0.0.0.0") && !hostIp.equals("0.0.0.0/0")
            && !hostIp.equals("::") && !hostIp.equals("::/0");
    }

    private boolean enableIpForward() {
        Log.i(TAG, "Enabling IPv4 forwarding");
        var result = RunUtils.run("echo 1 > /proc/sys/net/ipv4/ip_forward");
        return result.isSuccess();
    }
}
