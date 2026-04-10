package cn.classfun.droidvm.daemon.network.backend.iptables;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

final class ChainInfo {
    final IptablesBackend backend;
    final String table;
    final String parent;
    final String chain;

    ChainInfo(IptablesBackend backend, String table, String parent, String chain) {
        this.backend = backend;
        this.table = table;
        this.parent = parent;
        this.chain = chain;
    }

    ChainInfo(IptablesBackend backend, @NonNull ChainInfo parent, String chain) {
        this.backend = backend;
        this.table = parent.table;
        this.parent = parent.chain;
        this.chain = chain;
    }

    public void init(boolean insert) {
        backend.iptablesCmd(table, "-N", chain);
        backend.iptablesCmd(table, "-F", chain);
        if (parent != null && chain != null) {
            if (insert)
                backend.iptablesCmd(table, "-I", parent, "1", "-j", chain);
            else
                backend.iptablesCmd(table, "-A", parent, "-j", chain);
        }
    }

    public void deinit() {
        if (table != null && parent != null && chain != null)
            backend.iptablesCmd(table, "-D", parent, "-j", chain);
        if (table != null && chain != null) {
            backend.iptablesCmd(table, "-F", chain);
            backend.iptablesCmd(table, "-X", chain);
        }
    }

    public boolean append(@NonNull String... args) {
        var cmd = new ArrayList<String>();
        cmd.add("iptables");
        cmd.add("-t");
        cmd.add(table);
        cmd.add("-A");
        cmd.add(chain);
        cmd.addAll(Arrays.asList(args));
        return backend.iptables(cmd.toArray(new String[0]));
    }

    @SuppressWarnings("unused")
    public boolean delete(@NonNull String... args) {
        var cmd = new ArrayList<String>();
        cmd.add("iptables");
        cmd.add("-t");
        cmd.add(table);
        cmd.add("-D");
        cmd.add(chain);
        cmd.addAll(Arrays.asList(args));
        return backend.iptables(cmd.toArray(new String[0]));
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
