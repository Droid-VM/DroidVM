package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.utils.ProcessUtils;

/**
 * DHCPv4/DHCPv6/RA service for one Linux-bridge network: generates a
 * dnsmasq config covering the untagged domain and every VLAN
 * subinterface, plus a separate dhcp-hostsfile holding the static leases
 * (the hostsfile is the only part dnsmasq re-reads on SIGHUP, which is
 * how per-VM leases are added while the network runs).
 */
public final class Dnsmasq {
    private static final String TAG = "Dnsmasq";
    private static final String[] DEFAULT_DNS4 = {"8.8.8.8", "1.1.1.1"};
    private static final String[] DEFAULT_DNS6 = {"2001:4860:4860::8888"};
    private final NetworkInstance inst;
    private final ManagedProcess process;

    public Dnsmasq(@NonNull NetworkInstance inst) {
        this.inst = inst;
        this.process = new ManagedProcess(
            "dnsmasq", inst.item.optString("bridge_name", "")
        );
    }

    @NonNull
    private static String resolveDnsmasqBinary() {
        var prebuilt = getPrebuiltBinaryPath("dnsmasq");
        var file = new File(prebuilt);
        if (file.isFile() && file.canExecute())
            return prebuilt;
        return "dnsmasq";
    }

    /**
     * True when any VLAN needs DHCPv4, DHCPv6 or router advertisements.
     */
    public static boolean isNeeded(
        @NonNull List<VlanConfig> vlans,
        @NonNull Map<Integer, IPv6Network> liveV6
    ) {
        for (var vlan : vlans) {
            if (vlan.isDhcp4Enabled()) return true;
            var v6 = effectiveV6(vlan, liveV6);
            if (v6 != null && (vlan.isDhcp6Enabled() || vlan.isSlaacEnabled()))
                return true;
        }
        return false;
    }

    /**
     * The currently usable IPv6 network of a VLAN: the live DHCP-PD
     * delegation or the static CIDR, if any.
     */
    @Nullable
    public static IPv6Network effectiveV6(
        @NonNull VlanConfig vlan,
        @NonNull Map<Integer, IPv6Network> liveV6
    ) {
        var live = liveV6.get(vlan.getVlanId());
        if (live != null) return live;
        return vlan.getIpv6Network();
    }

    public boolean start(@NonNull Map<Integer, IPv6Network> liveV6) {
        var br = inst.item.optString("bridge_name", "");
        try {
            writeConf(liveV6);
            writeHosts();
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to write dnsmasq config for %s", br), e);
            return false;
        }
        var args = new ArrayList<String>();
        args.add(resolveDnsmasqBinary());
        args.add(fmt("--conf-file=%s", getConfFile(br)));
        return process.start(args);
    }

    public void stop() {
        process.stop();
        var pidFile = getPidFile(inst.item.optString("bridge_name", ""));
        if (new File(pidFile).exists())
            ProcessUtils.shellKillProcessFile(pidFile);
    }

    /** Restart with a new config (e.g. after a PD prefix change). */
    public boolean restart(@NonNull Map<Integer, IPv6Network> liveV6) {
        process.stop();
        return start(liveV6);
    }

    /** Regenerates the static lease hostsfile and signals dnsmasq to re-read it. */
    public void reloadStaticLeases() {
        try {
            writeHosts();
        } catch (Exception e) {
            Log.e(TAG, "Failed to rewrite dnsmasq hostsfile", e);
            return;
        }
        if (!process.signal(ProcessUtils.SIGHUP))
            Log.w(TAG, "Failed to send SIGHUP to dnsmasq");
    }

    public boolean isRunning() {
        return process.isRunning();
    }

    public int getExitCode() {
        return process.getExitCode();
    }

    private void writeConf(@NonNull Map<Integer, IPv6Network> liveV6) throws Exception {
        var br = inst.item.optString("bridge_name", "");
        var sb = new StringBuilder();
        sb.append("port=0\n");
        sb.append("no-resolv\n");
        sb.append("keep-in-foreground\n");
        sb.append("bind-interfaces\n");
        sb.append(fmt("pid-file=%s\n", getPidFile(br)));
        sb.append(fmt("dhcp-leasefile=%s\n", getDnsmasqLeaseFile(br)));
        sb.append(fmt("dhcp-hostsfile=%s\n", getHostsFile(br)));
        sb.append("log-dhcp\n");
        boolean anyV6 = false;
        var vlans = inst.getVlans();
        for (var vlan : vlans)
            sb.append(fmt("interface=%s\n", deviceOf(br, vlan)));
        for (var vlan : vlans) {
            var tag = fmt("v%d", vlan.getVlanId());
            sb.append(fmt("# vlan %d\n", vlan.getVlanId()));
            var net4 = vlan.getIpv4Network();
            if (vlan.isDhcp4Enabled() && net4 != null) {
                var start = net4.addressAtOffset(vlan.getDhcp4OffsetStart());
                var end = net4.addressAtOffset(vlan.getDhcp4OffsetEnd());
                sb.append(fmt(
                    "dhcp-range=set:%s,%s,%s,%s,12h\n",
                    tag, start, end, netmaskOf(net4)
                ));
                var dns = new ArrayList<String>();
                for (var s : vlan.getDnsServers())
                    if (!s.contains(":")) dns.add(s);
                if (dns.isEmpty()) dns = new ArrayList<>(List.of(DEFAULT_DNS4));
                sb.append(fmt(
                    "dhcp-option=tag:%s,option:dns-server,%s\n",
                    tag, String.join(",", dns)
                ));
            }
            var net6 = effectiveV6(vlan, liveV6);
            if (net6 != null) {
                if (vlan.isDhcp6Enabled()) {
                    var start = net6.addressAtOffset(
                        BigInteger.valueOf(vlan.getDhcp6OffsetStart()));
                    var end = net6.addressAtOffset(
                        BigInteger.valueOf(vlan.getDhcp6OffsetEnd()));
                    var mode = vlan.isSlaacEnabled() ? "slaac," : "";
                    sb.append(fmt(
                        "dhcp-range=set:%s,%s,%s,%s%d,12h\n",
                        tag, start, end, mode, net6.prefix()
                    ));
                    anyV6 = true;
                    var dns6 = new ArrayList<String>();
                    for (var s : vlan.getDnsServers())
                        if (s.contains(":")) dns6.add(fmt("[%s]", s));
                    if (dns6.isEmpty())
                        for (var s : DEFAULT_DNS6) dns6.add(fmt("[%s]", s));
                    sb.append(fmt(
                        "dhcp-option=tag:%s,option6:dns-server,%s\n",
                        tag, String.join(",", dns6)
                    ));
                } else if (vlan.isSlaacEnabled()) {
                    sb.append(fmt(
                        "dhcp-range=set:%s,%s,ra-only\n",
                        tag, net6.networkAddress()
                    ));
                    anyV6 = true;
                }
            }
        }
        if (anyV6) sb.append("enable-ra\n");
        writeFile(getConfFile(br), sb.toString());
    }

    private void writeHosts() throws Exception {
        var br = inst.item.optString("bridge_name", "");
        var netId = inst.getId().toString();
        var sb = new StringBuilder();
        var vms = inst.getStore().context.getVMs();
        if (vms != null) {
            var liveV6 = inst.getLiveV6Networks();
            vms.forEach((vmId, vm) -> vm.forEachNic(nic -> {
                if (!netId.equals(nic.getNetworkId())) return;
                var mac = nic.getMacAddress();
                if (mac == null) return;
                var vlan = nic.resolveDhcpVlan(inst);
                if (vlan == null) return;
                if (nic.isDhcp4LeaseEnabled() && vlan.isDhcp4Enabled()) {
                    var net4 = vlan.getIpv4Network();
                    if (net4 != null) {
                        try {
                            sb.append(fmt(
                                "%s,%s\n",
                                mac, net4.addressAtOffset(nic.getDhcp4Offset())
                            ));
                        } catch (Exception e) {
                            Log.w(TAG, fmt("Bad DHCPv4 offset for VM %s", vmId), e);
                        }
                    }
                }
                if (nic.isDhcp6LeaseEnabled() && vlan.isDhcp6Enabled()) {
                    var net6 = effectiveV6(vlan, liveV6);
                    if (net6 != null) {
                        try {
                            sb.append(fmt(
                                "%s,[%s]\n",
                                mac, net6.addressAtOffset(
                                    BigInteger.valueOf(nic.getDhcp6Offset()))
                            ));
                        } catch (Exception e) {
                            Log.w(TAG, fmt("Bad DHCPv6 offset for VM %s", vmId), e);
                        }
                    }
                }
            }));
        }
        writeFile(getHostsFile(br), sb.toString());
    }

    @NonNull
    private static String deviceOf(@NonNull String br, @NonNull VlanConfig vlan) {
        return vlan.isUntagged() ? br
            : LinuxNetwork.vlanSubInterface(br, vlan.getVlanId());
    }

    @NonNull
    private static String netmaskOf(@NonNull IPv4Network net) {
        long mask = net.mask();
        return fmt(
            "%d.%d.%d.%d",
            (mask >> 24) & 0xFF, (mask >> 16) & 0xFF,
            (mask >> 8) & 0xFF, mask & 0xFF
        );
    }

    private static void writeFile(@NonNull String path, @NonNull String content) throws Exception {
        var file = new File(path);
        var parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new RuntimeException(fmt("Failed to create directory %s", parent));
        try (var writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    @NonNull
    public static String getDnsmasqLeaseFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("dnsmasq-%s.leases", br));
    }

    @NonNull
    public static String getConfFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("dnsmasq-%s.conf", br));
    }

    @NonNull
    public static String getHostsFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("dnsmasq-%s.hosts", br));
    }

    @NonNull
    public static String getPidFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("dnsmasq-%s.pid", br));
    }
}
