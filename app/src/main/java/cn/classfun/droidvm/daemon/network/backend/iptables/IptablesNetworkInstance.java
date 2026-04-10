package cn.classfun.droidvm.daemon.network.backend.iptables;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.store.base.DataItem;

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
    }

    void initBasic() {
        boolean canIn = inst.item.optBoolean("nat", false) || inst.item.optBoolean("access", false);
        boolean canOut = inst.item.optBoolean("access", false);
        var extnetIn = backend.FIL_NET_EXTNET_IN.chain;
        var extnetOut = backend.FIL_NET_EXTNET_OUT.chain;
        var input = backend.FIL_NET_INPUT.chain;
        filFwd.addChain(br, br, null, null, "ACCEPT", null);
        filOut.addChain(null, br, null, null, "ACCEPT", null);
        for (var iter : inst.item.opt("ipv4_addresses", DataItem.newArray())) {
            IPv4Network addr = IPv4Network.parse(iter.getValue().asString());
            String router = new IPv4Network(addr.address(), 32).toString();
            String network = addr.network().toString();
            filIn.addChain(br, null, network, router, input, null);
            filFwd.addChain(br, null, network, null, canIn ? extnetOut : "DROP", null);
            filFwd.addChain(null, br, null, network, canOut ? extnetIn : "DROP", null);
            if (inst.item.optBoolean("nat", false)) {
                natPst.addChain(br, null, network, null, "MASQUERADE", null);
                natPst.addChain(null, br, null, network, "MASQUERADE", null);
            }
        }
        magPre.addChain(br, null, null, null, "ACCEPT", null);
        filFwd.addChain(br, null, null, null, "DROP", null);
        filFwd.addChain(null, br, null, null, "DROP", null);
    }
}
