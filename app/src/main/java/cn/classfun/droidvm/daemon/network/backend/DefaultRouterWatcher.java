package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.daemon.network.NetworkInstance;
import cn.classfun.droidvm.daemon.server.ServerContext;

public final class DefaultRouterWatcher {
    private static final String TAG = "DefaultRouterWatcher";
    private static final long POLL_INTERVAL_SEC = 5;
    private final ServerContext context;
    private final List<String> rules = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private String lastDefault = null;

    public DefaultRouterWatcher(@NonNull ServerContext context) {
        this.context = context;
    }

    @Nullable
    private String getCurrentDefault() {
        var result = runListQuiet("ip", "rule", "show");
        if (!result.isSuccess()) {
            result.printLog("ip-rule");
            throw new RuntimeException("Failed to list IP rules");
        }
        var data = result.getOutString();
        for (var line : data.split("\n")) {
            if (!line.contains("fwmark 0x0/0xffff")) continue;
            var idx = line.indexOf(" lookup ");
            if (idx < 0) continue;
            return line.substring(idx + (" lookup ".length())).trim();
        }
        return null;
    }

    private void clearRule() {
        for (var rule : rules)
            run(fmt("ip rule del %s", rule));
        rules.clear();
    }

    private void addRule(@NonNull String table, @NonNull String br) {
        var rule = fmt("from all iif %s lookup %s", br, table);
        run(fmt("ip rule add %s", rule));
        rules.add(rule);
    }

    private void setDefaultRouterForNetworks(@NonNull String net) {
        context.getNetworks().forEach((uuid, inst) ->
            addRule(net, inst.item.optString("bridge_name", "")));
    }

    private synchronized void runOnce() {
        try {
            var newDefault = getCurrentDefault();
            if (newDefault == null) return;
            if (lastDefault == null) {
                Log.i(TAG, fmt("Found default router changed %s", newDefault));
            } else {
                if (newDefault.equals(lastDefault)) return;
                Log.i(TAG, fmt("Default router changed from %s to %s", lastDefault, newDefault));
            }
            clearRule();
            setDefaultRouterForNetworks(newDefault);
            lastDefault = newDefault;
        } catch (Exception e) {
            Log.w(TAG, "Failed to update default router", e);
            stop();
        }
    }

    public synchronized void setForNewNetwork(@NonNull NetworkInstance inst) {
        if (lastDefault == null) return;
        addRule(lastDefault, inst.item.optString("bridge_name", ""));
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, TAG);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
            this::runOnce, 0, POLL_INTERVAL_SEC, TimeUnit.SECONDS
        );
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        clearRule();
    }
}
