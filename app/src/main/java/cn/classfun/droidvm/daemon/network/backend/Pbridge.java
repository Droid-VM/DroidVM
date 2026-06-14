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
    private static final int MAX_RESTARTS = 3;
    private final ManagedProcess process;
    private final String uplink;
    private final String bridge;
    private int restarts = 0;
    private volatile boolean active = false;

    public Pbridge(@NonNull String uplink, @NonNull String bridge) {
        this.uplink = uplink;
        this.bridge = bridge;
        this.process = new ManagedProcess("pbridge", bridge);
        this.process.setOnUnexpectedExit(this::onUnexpectedExit);
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
        restarts = 0;
        return process.start(buildArgs());
    }

    public void stop() {
        active = false;
        process.stop();
    }

    private void onUnexpectedExit() {
        if (!active) return;
        if (restarts >= MAX_RESTARTS) {
            Log.e(TAG, fmt(
                "pbridge for %s died %d times, giving up", bridge, restarts
            ));
            return;
        }
        restarts++;
        Log.w(TAG, fmt(
            "Restarting pbridge for %s (attempt %d/%d)", bridge, restarts, MAX_RESTARTS
        ));
        try {
            Thread.sleep(1000L * restarts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (active) process.start(buildArgs());
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
