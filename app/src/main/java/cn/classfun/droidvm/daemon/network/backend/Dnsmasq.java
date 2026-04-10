package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
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

    private void startDnsmasqProcess(
    ) {
        var bridge = inst.item.optString("bridge_name", "");
        var rangeStart = inst.item.optString("dhcp_range_start", "");
        var rangeEnd = inst.item.optString("dhcp_range_end", "");
        Log.i(TAG, fmt("Starting dnsmasq on %s (%s - %s)", bridge, rangeStart, rangeEnd));
        var pidFile = getDnsmasqPidFile();
        var leaseFile = getDnsmasqLeaseFile();
        try {
            var pb = new ProcessBuilder(
                "dnsmasq",
                fmt("--interface=%s", bridge),
                "--bind-interfaces",
                fmt("--dhcp-range=%s,%s,12h", rangeStart, rangeEnd),
                "--no-daemon",
                "--keep-in-foreground",
                fmt("--pid-file=%s", pidFile),
                fmt("--dhcp-leasefile=%s", leaseFile),
                "--log-queries",
                "--log-dhcp"
            );
            pb.redirectErrorStream(true);
            dnsmasqExitCode = 0;
            dnsmasqProcess = pb.start();
            Log.i(TAG, fmt("dnsmasq started on %s", bridge));
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to start dnsmasq on %s", bridge), e);
        }
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
