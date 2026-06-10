package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.lib.network.IPv4Address;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.store.network.NetworkState;
import cn.classfun.droidvm.lib.utils.ProcessUtils;

public final class Dnsmasq {
    private static final String TAG = "Dnsmasq";
    private final NetworkInstance inst;
    private Process dnsmasqProcess = null;
    private Thread dnsmasqMonitor = null;
    private volatile boolean dnsmasqRunning = false;
    private int dnsmasqExitCode = -1;

    public Dnsmasq(NetworkInstance inst) {
        this.inst = inst;
    }

    @NonNull
    private static String resolveDnsmasqBinary() {
        var prebuilt = getPrebuiltBinaryPath("dnsmasq");
        var file = new File(prebuilt);
        if (file.isFile() && file.canExecute())
            return prebuilt;
        return "dnsmasq";
    }

    private void startDnsmasqProcess(
    ) {
        var bridge = inst.item.optString("bridge_name", "");
        var rangeStart = inst.item.optString("dhcp_range_start", "");
        var rangeEnd = inst.item.optString("dhcp_range_end", "");
        var router = findRouterAddress(rangeStart);
        var dnsmasq = resolveDnsmasqBinary();
        Log.i(TAG, fmt("Starting dnsmasq on %s (%s - %s) using %s",
            bridge, rangeStart, rangeEnd, dnsmasq));
        var pidFile = getDnsmasqPidFile();
        var leaseFile = getDnsmasqLeaseFile();
        try {
            var args = new ArrayList<String>();
            args.add(dnsmasq);
            args.add(fmt("--interface=%s", bridge));
            args.add("--bind-interfaces");
            args.add(fmt("--dhcp-range=%s,%s,12h", rangeStart, rangeEnd));
            if (router != null)
                args.add(fmt("--dhcp-option=option:router,%s", router));
            var dnsServers = new ArrayList<String>();
            for (var iter : inst.item.get("dns_servers")) {
                var s = iter.getValue().asString();
                if (s != null && !s.isEmpty()) dnsServers.add(s);
            }
            if (!dnsServers.isEmpty())
                args.add(fmt("--dhcp-option=option:dns-server,%s", String.join(",", dnsServers)));
            args.add("--port=0");
            args.add("--no-resolv");
            args.add("--no-daemon");
            args.add("--keep-in-foreground");
            args.add(fmt("--pid-file=%s", pidFile));
            args.add(fmt("--dhcp-leasefile=%s", leaseFile));
            args.add("--log-queries");
            args.add("--log-dhcp");
            var pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            dnsmasqExitCode = 0;
            dnsmasqProcess = pb.start();
            Log.i(TAG, fmt("dnsmasq started on %s", bridge));
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to start dnsmasq on %s", bridge), e);
        }
    }

    @Nullable
    private String findRouterAddress(@NonNull String rangeStart) {
        IPv4Address dhcpStart = null;
        try {
            if (!rangeStart.isEmpty()) dhcpStart = IPv4Address.parse(rangeStart);
        } catch (Exception ignored) {
        }

        IPv4Network fallback = null;
        for (var iter : inst.item.get("ipv4_addresses")) {
            try {
                var cidr = IPv4Network.parse(iter.getValue().asString());
                if (fallback == null) fallback = cidr;
                if (dhcpStart != null && cidr.contains(dhcpStart))
                    return cidr.address().toString();
            } catch (Exception ignored) {
            }
        }
        return fallback != null ? fallback.address().toString() : null;
    }

    private void stopDnsmasqProcess(@Nullable Process process) {
        var bridge = inst.item.optString("bridge_name", "");
        Log.i(TAG, fmt("Stopping dnsmasq on %s", bridge));
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        var pidFile = getDnsmasqPidFile();
        if (new File(pidFile).exists())
            ProcessUtils.shellKillProcessFile(pidFile);
    }

    public void startDnsmasq() {
        stopDnsmasq();
        var br = inst.item.optString("bridge_name", "");
        startDnsmasqProcess();
        if (dnsmasqProcess == null) {
            Log.e(TAG, fmt("dnsmasq failed to start on %s", br));
            dnsmasqRunning = false;
            return;
        }
        dnsmasqRunning = true;
        Log.i(TAG, fmt("dnsmasq running on %s", br));
        dnsmasqMonitor = new Thread(this::dnsmasqMonitorThread, fmt("dnsmasq-mon-%s", br));
        dnsmasqMonitor.setDaemon(true);
        dnsmasqMonitor.start();
    }

    private void dnsmasqMonitorThread() {
        var br = inst.item.optString("bridge_name", "");
        try {
            try (var reader = new BufferedReader(
                new InputStreamReader(dnsmasqProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null)
                    Log.d(fmt("dnsmasq-%s", br), line);
            }
            int code = dnsmasqProcess.waitFor();
            dnsmasqExitCode = code;
            dnsmasqRunning = false;
            if (inst.getState() == NetworkState.RUNNING) {
                Log.w(TAG, fmt("dnsmasq on %s exited unexpectedly (code %d)", br, code));
            } else {
                Log.i(TAG, fmt("dnsmasq on %s exited (code %d)", br, code));
            }
        } catch (InterruptedException | InterruptedIOException e) {
            Thread.currentThread().interrupt();
            Log.d(TAG, fmt("dnsmasq monitor on %s interrupted", br));
        } catch (Exception e) {
            Log.e(TAG, fmt("dnsmasq monitor on %s failed", br), e);
        } finally {
            dnsmasqRunning = false;
        }
    }

    public void stopDnsmasq() {
        if (!dnsmasqRunning) return;
        if (dnsmasqMonitor != null) {
            dnsmasqMonitor.interrupt();
            dnsmasqMonitor = null;
        }
        if (dnsmasqProcess != null || inst.item.optBoolean("dhcp_enabled", false)) {
            stopDnsmasqProcess(dnsmasqProcess);
            dnsmasqProcess = null;
        }
        dnsmasqRunning = false;
    }

    @NonNull
    public String getDnsmasqLeaseFile() {
        return getDnsmasqLeaseFile(inst.item.optString("bridge_name", ""));
    }

    @NonNull
    public String getDnsmasqPidFile() {
        return getDnsmasqPidFile(inst.item.optString("bridge_name", ""));
    }

    @NonNull
    public static String getDnsmasqLeaseFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("dnsmasq-%s.leases", br));
    }

    @NonNull
    public static String getDnsmasqPidFile(@NonNull String br) {
        return pathJoin(DATA_DIR, "run", fmt("dnsmasq-%s.pid", br));
    }

    public int getDnsmasqExitCode() {
        return dnsmasqExitCode;
    }

    public boolean isDnsmasqRunning() {
        return dnsmasqRunning;
    }
}
