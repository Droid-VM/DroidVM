package cn.classfun.droidvm.daemon.network.backend.iptables;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.network.backend.FirewallHelper;
import cn.classfun.droidvm.lib.network.IPv6Network;
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
    final ChainInfo FIL6_NET_EXTNET_IN = new ChainInfo(this, true, "filter", null, "droidvm_ext_in");
    final ChainInfo FIL6_NET_EXTNET_OUT = new ChainInfo(this, true, "filter", null, "droidvm_ext_out");
    final ChainInfo FIL6_IN = new ChainInfo(this, true, "filter", "INPUT", "droidvm_in");
    final ChainInfo FIL6_OUT = new ChainInfo(this, true, "filter", "OUTPUT", "droidvm_out");
    final ChainInfo FIL6_FWD = new ChainInfo(this, true, "filter", "FORWARD", "droidvm_fwd");
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
        FIL6_NET_EXTNET_IN,
        FIL6_NET_EXTNET_OUT,
        FIL6_IN,
        FIL6_OUT,
        FIL6_FWD,
    };
    private static final String[] EXT_IFACES = {"wlan+", "eth+", "rmnet_data+"};

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

    boolean iptablesCmd(@NonNull String binary, @NonNull String table, @NonNull String... args) {
        var cmd = new ArrayList<String>();
        cmd.add(binary);
        cmd.add("-t");
        cmd.add(table);
        cmd.addAll(Arrays.asList(args));
        return iptables(cmd.toArray(new String[0]));
    }

    @Override
    public boolean initialize() {
        Log.i(TAG, "Initializing iptables firewall chains");
        for (var def : DEFS)
            def.init(true);
        FIL_FWD.append("-m", "state", "--state", "RELATED,ESTABLISHED", "-j", "ACCEPT");
        FIL6_FWD.append("-m", "state", "--state", "RELATED,ESTABLISHED", "-j", "ACCEPT");
        for (var iface : EXT_IFACES) {
            FIL_NET_EXTNET_OUT.append("-o", iface, "-j", "ACCEPT");
            FIL_NET_EXTNET_IN.append("-i", iface, "-j", "ACCEPT");
            FIL6_NET_EXTNET_OUT.append("-o", iface, "-j", "ACCEPT");
            FIL6_NET_EXTNET_IN.append("-i", iface, "-j", "ACCEPT");
        }
        FIL_NET_EXTNET_OUT.append("-j", "DROP");
        FIL_NET_EXTNET_IN.append("-j", "DROP");
        FIL6_NET_EXTNET_OUT.append("-j", "DROP");
        FIL6_NET_EXTNET_IN.append("-j", "DROP");
        enableIpForward();
        return true;
    }

    @Override
    public boolean shutdown() {
        Log.i(TAG, "Shutting down iptables firewall chains");
        for (var def : DEFS)
            def.deinit();
        return true;
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
    public void addLiveV6Subnet(
        @NonNull NetworkInstance inst, @NonNull String dev, @NonNull IPv6Network net
    ) {
        new IptablesNetworkInstance(this, inst).addLiveV6Subnet(dev, net);
    }

    @Override
    public void removeLiveV6Subnet(
        @NonNull NetworkInstance inst, @NonNull String dev, @NonNull IPv6Network net
    ) {
        new IptablesNetworkInstance(this, inst).removeLiveV6Subnet(dev, net);
    }

    @Override
    public boolean applyForwardBase(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        int guestStart, int guestEnd, @Nullable String hairpinSubnet
    ) {
        var fwd = new ChainInfo(this, "filter", null, fmt("droidvm_fwd_%s", bridge));
        boolean ok = fwd.insert(fwdSpec(bridge, guestIp, protocol, guestStart, guestEnd));
        if (hairpinSubnet != null) {
            var pst = new ChainInfo(this, "nat", null, fmt("droidvm_pst_%s", bridge));
            ok &= pst.append(hairpinSpec(
                protocol, hairpinSubnet, guestIp, guestStart, guestEnd));
        }
        Log.i(TAG, fmt("Apply forward base %s -> %s:%s on %s (ok=%b)",
            protocol, guestIp, portRange(guestStart, guestEnd, ':'), bridge, ok));
        return ok;
    }

    @Override
    public boolean removeForwardBase(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        int guestStart, int guestEnd, @Nullable String hairpinSubnet
    ) {
        var fwd = new ChainInfo(this, "filter", null, fmt("droidvm_fwd_%s", bridge));
        boolean ok = fwd.delete(fwdSpec(bridge, guestIp, protocol, guestStart, guestEnd));
        if (hairpinSubnet != null) {
            var pst = new ChainInfo(this, "nat", null, fmt("droidvm_pst_%s", bridge));
            ok &= pst.delete(hairpinSpec(
                protocol, hairpinSubnet, guestIp, guestStart, guestEnd));
        }
        Log.i(TAG, fmt("Remove forward base %s -> %s:%s on %s (ok=%b)",
            protocol, guestIp, portRange(guestStart, guestEnd, ':'), bridge, ok));
        return ok;
    }

    @Override
    public boolean applyDnat(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @NonNull String hostIp, int hostStart, int hostEnd, int guestStart, int guestEnd
    ) {
        var pre = new ChainInfo(this, "nat", null, fmt("droidvm_pre_%s", bridge));
        boolean ok = pre.append(
            dnatSpec(protocol, hostIp, hostStart, hostEnd, guestIp, guestStart, guestEnd));
        Log.i(TAG, fmt("Apply DNAT %s %s:%s -> %s:%s on %s (ok=%b)",
            protocol, hostIp, portRange(hostStart, hostEnd, ':'),
            guestIp, portRange(guestStart, guestEnd, ':'), bridge, ok));
        return ok;
    }

    @Override
    public boolean removeDnat(
        @NonNull String bridge, @NonNull String guestIp, @NonNull String protocol,
        @NonNull String hostIp, int hostStart, int hostEnd, int guestStart, int guestEnd
    ) {
        var pre = new ChainInfo(this, "nat", null, fmt("droidvm_pre_%s", bridge));
        boolean ok = pre.delete(
            dnatSpec(protocol, hostIp, hostStart, hostEnd, guestIp, guestStart, guestEnd));
        Log.i(TAG, fmt("Remove DNAT %s %s:%s -> %s:%s on %s (ok=%b)",
            protocol, hostIp, portRange(hostStart, hostEnd, ':'),
            guestIp, portRange(guestStart, guestEnd, ':'), bridge, ok));
        return ok;
    }

    /**
     * NAT loopback: a guest in hairpinSubnet hitting the forward's guest
     * target gets its source masqueraded to the gateway, so the target's
     * reply routes back through the gateway instead of straight over L2
     * (where the originator would see an unexpected source).
     */
    @NonNull
    private static String[] hairpinSpec(
        @NonNull String protocol, @NonNull String hairpinSubnet,
        @NonNull String guestIp, int guestStart, int guestEnd
    ) {
        return new String[]{
            "-s", hairpinSubnet, "-d", guestIp, "-p", protocol,
            "--dport", portRange(guestStart, guestEnd, ':'), "-j", "MASQUERADE"
        };
    }

    @NonNull
    private static String portRange(int start, int end, char sep) {
        return start == end
            ? String.valueOf(start)
            : fmt("%d%c%d", start, sep, end);
    }

    @NonNull
    private static String[] dnatSpec(
        @NonNull String protocol, @Nullable String hostIp, int hostStart, int hostEnd,
        @NonNull String guestIp, int guestStart, int guestEnd
    ) {
        var spec = new ArrayList<String>();
        spec.add("-p");
        spec.add(protocol);
        if (isSpecificHost(hostIp)) {
            spec.add("-d");
            spec.add(hostIp);
        }
        spec.add("--dport");
        spec.add(portRange(hostStart, hostEnd, ':'));
        spec.add("-j");
        spec.add("DNAT");
        spec.add("--to-destination");
        // iptables DNAT port ranges use a dash: ip:8000-9000
        spec.add(fmt("%s:%s", guestIp, portRange(guestStart, guestEnd, '-')));
        return spec.toArray(new String[0]);
    }

    @NonNull
    private static String[] fwdSpec(
        @NonNull String bridge, @NonNull String guestIp,
        @NonNull String protocol, int guestStart, int guestEnd
    ) {
        return new String[]{
            "-o", fmt("%s+", bridge), "-d", guestIp, "-p", protocol,
            "--dport", portRange(guestStart, guestEnd, ':'), "-j", "ACCEPT"
        };
    }

    /** A wildcard host_ip (empty / 0.0.0.0[/0] / ::[/0]) must omit -d, or the DNAT never matches. */
    private static boolean isSpecificHost(@Nullable String hostIp) {
        return hostIp != null && !hostIp.isEmpty()
            && !hostIp.equals("0.0.0.0") && !hostIp.equals("0.0.0.0/0")
            && !hostIp.equals("::") && !hostIp.equals("::/0");
    }

    private boolean enableIpForward() {
        Log.i(TAG, "Enabling IP forwarding");
        var result = RunUtils.run("echo 1 > /proc/sys/net/ipv4/ip_forward");
        RunUtils.run("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding");
        return result.isSuccess();
    }
}
