package cn.classfun.droidvm.daemon.network.backend.iptables;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

final class ChainInfo {
    final IptablesBackend backend;
    final boolean ipv6;
    final String table;
    final String parent;
    final String chain;

    ChainInfo(IptablesBackend backend, String table, String parent, String chain) {
        this(backend, false, table, parent, chain);
    }

    ChainInfo(IptablesBackend backend, boolean ipv6, String table, String parent, String chain) {
        this.backend = backend;
        this.ipv6 = ipv6;
        this.table = table;
        this.parent = parent;
        this.chain = chain;
    }

    ChainInfo(IptablesBackend backend, @NonNull ChainInfo parent, String chain) {
        this.backend = backend;
        this.ipv6 = parent.ipv6;
        this.table = parent.table;
        this.parent = parent.chain;
        this.chain = chain;
    }

    @NonNull
    String binary() {
        return ipv6 ? "ip6tables" : "iptables";
    }

    public void init(boolean insert) {
        backend.iptablesCmd(binary(), table, "-N", chain);
        backend.iptablesCmd(binary(), table, "-F", chain);
        if (parent != null && chain != null) {
            if (insert)
                backend.iptablesCmd(binary(), table, "-I", parent, "1", "-j", chain);
            else
                backend.iptablesCmd(binary(), table, "-A", parent, "-j", chain);
        }
    }

    public void deinit() {
        if (table != null && parent != null && chain != null)
            backend.iptablesCmd(binary(), table, "-D", parent, "-j", chain);
        if (table != null && chain != null) {
            backend.iptablesCmd(binary(), table, "-F", chain);
            backend.iptablesCmd(binary(), table, "-X", chain);
        }
    }

    private boolean rule(@NonNull String op, @Nullable String pos, @NonNull String... args) {
        var cmd = new ArrayList<String>();
        cmd.add(binary());
        cmd.add("-t");
        cmd.add(table);
        cmd.add(op);
        cmd.add(chain);
        if (pos != null) cmd.add(pos);
        cmd.addAll(Arrays.asList(args));
        return backend.iptables(cmd.toArray(new String[0]));
    }

    public boolean append(@NonNull String... args) {
        return rule("-A", null, args);
    }

    public boolean insert(@NonNull String... args) {
        return rule("-I", "1", args);
    }

    @SuppressWarnings("unused")
    public boolean delete(@NonNull String... args) {
        return rule("-D", null, args);
    }

    @SuppressWarnings("UnusedReturnValue")
    boolean addChain(
        @Nullable String inIntf,
        @Nullable String outIntf,
        @Nullable String srcAddr,
        @Nullable String dstAddr,
        @Nullable String jump,
        @Nullable String[] additional
    ) {
        var list = new ArrayList<String>();
        if (inIntf != null) {
            list.add("-i");
            list.add(inIntf);
        }
        if (outIntf != null) {
            list.add("-o");
            list.add(outIntf);
        }
        if (srcAddr != null) {
            list.add("-s");
            list.add(srcAddr);
        }
        if (dstAddr != null) {
            list.add("-d");
            list.add(dstAddr);
        }
        if (jump != null) {
            list.add("-j");
            list.add(jump);
        }
        if (additional != null)
            list.addAll(Arrays.asList(additional));
        return append(list.toArray(new String[0]));
    }
}
