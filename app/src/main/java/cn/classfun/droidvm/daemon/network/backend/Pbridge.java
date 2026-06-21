package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * pseudo-bridge-rs daemon for L2 networks whose uplink cannot be enslaved
 * into a Linux bridge (Wi-Fi STA / IFF_DONT_BRIDGE): performs MAC-NAT in
 * kernel via eBPF and bridges through a veth pair it manages itself.
 */
public final class Pbridge {
    private static final String TAG = "Pbridge";
    private final ManagedProcess process;
    private final String uplink;
    private final String bridge;
    private volatile boolean active = false;

    public Pbridge(@NonNull String uplink, @NonNull String bridge) {
        this.uplink = uplink;
        this.bridge = bridge;
        this.process = new ManagedProcess("pbridge", bridge);
    }

    @NonNull
    private List<String> buildArgs() {
        return List.of(
            getAssetBinaryPath("pbridge"),
            "-i", uplink,
            "-e", "ebpf",
            "-m", "fwd-with-offload",
            "-b", bridge,
            "--offload-workaround", "v4,v6",
            // pin the proxy-address tag so host-IP discovery can exclude them
            "--offload-workaround-magic",
            String.valueOf(cn.classfun.droidvm.lib.Constants.PBRIDGE_OFFLOAD_MAGIC),
            "--arp-keepalive", "10",
            "--loglevel", "info"
        );
    }

    public boolean start() {
        active = true;
        return process.start(buildArgs());
    }

    public void stop() {
        active = false;
        process.stop();
    }

    /**
     * Restarts pbridge if it died (stateless eBPF forwarder; a fresh process
     * re-establishes the data path). Called on the watchdog's 5s tick while the
     * network is running. Returns true when healthy.
     */
    public synchronized boolean reconcile() {
        if (!active || process.isRunning()) return true;
        Log.w(TAG, fmt(
            "pbridge for %s is down (exit=%d), restarting", bridge, process.getExitCode()
        ));
        return process.start(buildArgs());
    }

    public boolean isRunning() {
        return process.isRunning();
    }

    public int getExitCode() {
        return process.getExitCode();
    }

    @NonNull
    public java.util.List<String> getLog() {
        return process.getLog();
    }
}
