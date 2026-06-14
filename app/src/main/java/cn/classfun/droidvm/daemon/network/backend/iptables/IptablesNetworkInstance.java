package cn.classfun.droidvm.daemon.network.backend.iptables;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.network.backend.LinuxNetwork;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.network.VlanConfig;

/**
 * Per-network iptables/ip6tables chains for an L3-routed Linux bridge:
 * per-VLAN subnet accept rules, MASQUERADE for SNAT-enabled VLANs and the
 * per-bridge chains that hold DNAT port forwards.
 */
final class IptablesNetworkInstance {
    final IptablesBackend backend;
    final NetworkInstance inst;
    final String br;
    final ChainInfo filIn;
    final ChainInfo filOut;
    final ChainInfo filFwd;
    final ChainInfo natPre;
    final ChainInfo natPst;
    final ChainInfo magIn;
    final ChainInfo magOut;
    final ChainInfo magFwd;
    final ChainInfo magPre;
    final ChainInfo magPst;
    final ChainInfo rawOut;
    final ChainInfo rawPre;
    final ChainInfo filIn6;
    final ChainInfo filOut6;
    final ChainInfo filFwd6;

    IptablesNetworkInstance(IptablesBackend backend, @NonNull NetworkInstance inst) {
        this.backend = backend;
        this.inst = inst;
        br = inst.item.optString("bridge_name", "");
        filIn = new ChainInfo(backend, backend.FIL_IN, fmt("droidvm_in_%s", br));
        filOut = new ChainInfo(backend, backend.FIL_OUT, fmt("droidvm_out_%s", br));
        filFwd = new ChainInfo(backend, backend.FIL_FWD, fmt("droidvm_fwd_%s", br));
        natPre = new ChainInfo(backend, backend.NAT_PRE, fmt("droidvm_pre_%s", br));
        natPst = new ChainInfo(backend, backend.NAT_PST, fmt("droidvm_pst_%s", br));
        magIn = new ChainInfo(backend, backend.MAG_IN, fmt("droidvm_in_%s", br));
        magOut = new ChainInfo(backend, backend.MAG_OUT, fmt("droidvm_out_%s", br));
        magFwd = new ChainInfo(backend, backend.MAG_FWD, fmt("droidvm_fwd_%s", br));
        magPre = new ChainInfo(backend, backend.MAG_PRE, fmt("droidvm_pre_%s", br));
        magPst = new ChainInfo(backend, backend.MAG_PST, fmt("droidvm_pst_%s", br));
        rawOut = new ChainInfo(backend, backend.RAW_OUT, fmt("droidvm_out_%s", br));
        rawPre = new ChainInfo(backend, backend.RAW_PRE, fmt("droidvm_pre_%s", br));
        filIn6 = new ChainInfo(backend, backend.FIL6_IN, fmt("droidvm_in_%s", br));
        filOut6 = new ChainInfo(backend, backend.FIL6_OUT, fmt("droidvm_out_%s", br));
        filFwd6 = new ChainInfo(backend, backend.FIL6_FWD, fmt("droidvm_fwd_%s", br));
    }

    void init() {
        filIn.init(false);
        filOut.init(false);
        filFwd.init(false);
        natPre.init(false);
        natPst.init(false);
        magIn.init(false);
        magOut.init(false);
        magFwd.init(false);
        magPre.init(false);
        magPst.init(false);
        rawOut.init(false);
        rawPre.init(false);
        filIn6.init(false);
        filOut6.init(false);
        filFwd6.init(false);
    }

    void deinit() {
        filIn.deinit();
        filOut.deinit();
        filFwd.deinit();
        natPre.deinit();
        natPst.deinit();
        magIn.deinit();
        magOut.deinit();
        magFwd.deinit();
        magPre.deinit();
        magPst.deinit();
        rawOut.deinit();
        rawPre.deinit();
        filIn6.deinit();
        filOut6.deinit();
        filFwd6.deinit();
    }

    @NonNull
    private String deviceOf(@NonNull VlanConfig vlan) {
        return LinuxNetwork.vlanDevice(br, vlan.getVlanId());
    }

    void initBasic() {
        var extnetIn = backend.FIL_NET_EXTNET_IN.chain;
        var extnetOut = backend.FIL_NET_EXTNET_OUT.chain;
        var extnetIn6 = backend.FIL6_NET_EXTNET_IN.chain;
        var extnetOut6 = backend.FIL6_NET_EXTNET_OUT.chain;
        var input = backend.FIL_NET_INPUT.chain;
        // bridged + inter-VLAN routed traffic within this network
        var wildcard = fmt("%s+", br);
        filFwd.addChain(wildcard, wildcard, null, null, "ACCEPT", null);
        filFwd6.addChain(wildcard, wildcard, null, null, "ACCEPT", null);
        filOut.addChain(null, wildcard, null, null, "ACCEPT", null);
        filOut6.addChain(null, wildcard, null, null, "ACCEPT", null);
        for (var vlan : inst.getVlans()) {
            var dev = deviceOf(vlan);
            boolean snat4 = vlan.isIpv4Snat();
            var net4 = vlan.getIpv4Network();
            if (net4 != null)
                addV4Subnet(dev, net4, snat4, input, extnetIn, extnetOut);
            for (var cidr : vlan.getIpv4Secondary()) {
                try {
                    addV4Subnet(dev, IPv4Network.parse(cidr),
                        snat4, input, extnetIn, extnetOut);
                } catch (Exception ignored) {
                }
            }
            var net6 = vlan.getIpv6Network();
            if (net6 != null) addV6Subnet(dev, net6, extnetIn6, extnetOut6);
            for (var cidr : vlan.getIpv6Secondary()) {
                try {
                    addV6Subnet(dev, IPv6Network.parse(cidr), extnetIn6, extnetOut6);
                } catch (Exception ignored) {
                }
            }
        }
        magPre.addChain(wildcard, null, null, null, "ACCEPT", null);
        filFwd.addChain(wildcard, null, null, null, "DROP", null);
        filFwd.addChain(null, wildcard, null, null, "DROP", null);
        filFwd6.addChain(wildcard, null, null, null, "DROP", null);
        filFwd6.addChain(null, wildcard, null, null, "DROP", null);
    }

    private void addV4Subnet(
        @NonNull String dev, @NonNull IPv4Network addr, boolean snat,
        String input, String extnetIn, String extnetOut
    ) {
        var router = new IPv4Network(addr.address(), 32).toString();
        var network = addr.toNetworkString();
        filIn.addChain(dev, null, network, router, input, null);
        filFwd.addChain(dev, null, network, null, extnetOut, null);
        filFwd.addChain(null, dev, null, network, extnetIn, null);
        if (snat) {
            // SNAT guest -> external only. POSTROUTING has no input
            // interface (`-i` is invalid there), so match on the source
            // subnet leaving via a non-bridge interface; `! -o {br}+`
            // excludes inter-VLAN traffic, which keeps its real source.
            natPst.append("-s", network, "!", "-o", fmt("%s+", br), "-j", "MASQUERADE");
        }
    }

    private void addV6Subnet(
        @NonNull String dev, @NonNull IPv6Network addr,
        String extnetIn6, String extnetOut6
    ) {
        var network = addr.toNetworkString();
        var router = fmt("%s/128", addr.address());
        filIn6.addChain(dev, null, network, router, "ACCEPT", null);
        filFwd6.addChain(dev, null, network, null, extnetOut6, null);
        filFwd6.addChain(null, dev, null, network, extnetIn6, null);
    }

    /**
     * Adds rules for a delegated prefix acquired after network start;
     * inserted at the top so they precede the trailing DROP rules.
     */
    void addLiveV6Subnet(@NonNull String dev, @NonNull IPv6Network addr) {
        var network = addr.toNetworkString();
        var router = fmt("%s/128", addr.address());
        filIn6.insert("-i", dev, "-s", network, "-d", router, "-j", "ACCEPT");
        filFwd6.insert("-i", dev, "-s", network,
            "-j", backend.FIL6_NET_EXTNET_OUT.chain);
        filFwd6.insert("-o", dev, "-d", network,
            "-j", backend.FIL6_NET_EXTNET_IN.chain);
    }

    void removeLiveV6Subnet(@NonNull String dev, @NonNull IPv6Network addr) {
        var network = addr.toNetworkString();
        var router = fmt("%s/128", addr.address());
        filIn6.delete("-i", dev, "-s", network, "-d", router, "-j", "ACCEPT");
        filFwd6.delete("-i", dev, "-s", network,
            "-j", backend.FIL6_NET_EXTNET_OUT.chain);
        filFwd6.delete("-o", dev, "-d", network,
            "-j", backend.FIL6_NET_EXTNET_IN.chain);
    }
}
