// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.network;

import static android.widget.Toast.LENGTH_LONG;
import static java.util.concurrent.TimeUnit.SECONDS;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class NetworkActions {
    private static final String TAG = "NetworkActions";

    public interface Callback {
        void onFinished(boolean success);
    }

    private NetworkActions() {
    }

    public static void createAndStart(
        @NonNull NetworkConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull Runnable onStarted
    ) {
        var conn = DaemonConnection.getInstance();
        DaemonConnection.OnError err = e ->
            showDaemonError(mainHandler, ui, e);
        DaemonConnection.OnUnsuccessful f = resp ->
            showError(mainHandler, ui, resp.optString("message", "Unknown error"));
        DaemonConnection.OnResponse onStart = resp -> {
            var netId = resp.optString("network_id", "");
            if (netId.isEmpty()) return;
            conn.buildRequest("network_start")
                .put("network_id", netId)
                .onResponse(r -> onStarted.run())
                .onUnsuccessful(f)
                .onError(err)
                .invoke();
        };
        DaemonConnection.OnResponse onExists = resp -> {
            var exists = resp.optBoolean("exists", false);
            conn.buildRequest(exists ? "network_modify" : "network_create")
                .put("config", config)
                .onResponse(onStart)
                .onUnsuccessful(f)
                .onError(err)
                .invoke();
        };
        conn.buildRequest("network_exists")
            .put("network_id", config.getId())
            .onResponse(onExists)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    /**
     * Makes the daemon's picture of a network match the store's: it creates the network there
     * when the daemon does not know it yet, and modifies it when it does. Registering is not
     * starting -- the instance lands STOPPED either way.
     *
     * <p>Every network the app writes to networks.json has to go through here, because the
     * daemon only re-reads that file when it starts. A network it has never been told about is
     * one it cannot resolve: a VM whose NIC names it refuses to start ("Network ... not found"),
     * and an export of that VM silently packs no network at all.
     *
     * <p>Best effort and silent: a daemon that is not running yet will read the network out of
     * networks.json when it starts, which is the case the setup wizard is normally in.
     */
    public static void syncToDaemon(@NonNull NetworkConfig config) {
        var conn = DaemonConnection.getInstance();
        DaemonConnection.OnUnsuccessful f = r ->
            Log.w(TAG, fmt("Daemon refused the network sync: %s", r.optString("message", "")));
        DaemonConnection.OnError err = e -> Log.w(TAG, "Daemon network sync failed", e);
        conn.buildRequest("network_exists")
            .put("network_id", config.getId())
            .onResponse(resp -> conn
                .buildRequest(resp.optBoolean("exists", false)
                    ? "network_modify" : "network_create")
                .put("config", config)
                .onUnsuccessful(f)
                .onError(err)
                .invoke())
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    public static void deleteNetwork(
        @NonNull Context context,
        @NonNull Handler mainHandler,
        @NonNull UUID networkId,
        @NonNull NetworkStore netStore,
        @NonNull Callback callback
    ) {
        var netId = networkId.toString();
        var vmStore = new VMStore();
        runOnPool(() -> {
            vmStore.load(context);
            var runningNames = new ArrayList<String>();
            try {
                var latch = getCountDownLatch(vmStore, netId, runningNames);
                //noinspection ResultOfMethodCallIgnored
                latch.await(5, SECONDS);
            } catch (Exception e) {
                Log.e(TAG, "Failed to check running VMs", e);
            }
            if (!runningNames.isEmpty()) {
                mainHandler.post(() -> {
                    Toast.makeText(context,
                        context.getString(R.string.network_delete_in_use,
                            String.join(", ", runningNames)),
                        LENGTH_LONG).show();
                    callback.onFinished(false);
                });
                return;
            }
            boolean vmModified = false;
            var allVms = new ArrayList<VMConfig>();
            vmStore.forEach((id, cfg) -> allVms.add(cfg));
            for (var vmCfg : allVms) {
                var nets = vmCfg.item.opt("networks", DataItem.newArray());
                for (int i = nets.size() - 1; i >= 0; i--) {
                    if (netId.equals(nets.get(i).optString("network_id", ""))) {
                        nets.remove(i);
                        vmModified = true;
                    }
                }
            }
            if (vmModified) vmStore.save(context);
            DaemonConnection.OnUnsuccessful f = r ->
                Log.w(TAG, fmt("Daemon network_delete failed: %s", r.optString("message", "")));
            DaemonConnection.getInstance().buildRequest("network_delete")
                .put("network_id", netId)
                .onUnsuccessful(f)
                .onError(e -> Log.w(TAG, "Daemon network_delete error", e))
                .invoke();
            netStore.removeById(networkId);
            netStore.save(context);
            mainHandler.post(() -> callback.onFinished(true));
        });
    }

    @NonNull
    private static CountDownLatch getCountDownLatch(
        VMStore vmStore,
        String netId,
        ArrayList<String> runningNames
    ) {
        var latch = new CountDownLatch(1);
        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(resp -> {
                collectRunningVMsUsingNetwork(resp, vmStore, netId, runningNames);
                latch.countDown();
            })
            .onUnsuccessful(r -> latch.countDown())
            .onError(e -> {
                Log.w(TAG, "Failed to query VM list", e);
                latch.countDown();
            })
            .invoke();
        return latch;
    }

    private static void collectRunningVMsUsingNetwork(
        @NonNull JSONObject resp,
        @NonNull VMStore vmStore,
        @NonNull String netId,
        @NonNull ArrayList<String> out
    ) {
        try {
            JsonUtils.forEachArray(resp, "data", (JSONObject vm) -> {
                // vm_list reports the enum name ("STOPPED"); vm_status lower-cases it. Compare
                // either way -- a case-sensitive match here read every stopped VM as running and
                // so refused to delete any network a VM had ever been attached to.
                if (vm.optString("state", "stopped").equalsIgnoreCase("stopped")) return;
                var vmId = vm.optString("id", "");
                var vmCfg = vmStore.findById(vmId);
                if (vmCfg == null) return;
                var nets = vmCfg.item.opt("networks", DataItem.newArray());
                if (nets.isEmpty()) return;
                for (var iter : nets) {
                    if (netId.equals(iter.getValue().optString("network_id", ""))) {
                        var name = vmCfg.getName();
                        out.add(name != null ? name : vmId);
                        break;
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void showError(@NonNull Handler handler, UIContext ui, String msg) {
        handler.post(() -> {
            if (!ui.isAlive()) return;
            Toast.makeText(ui.getContext(), msg, LENGTH_LONG).show();
        });
    }

    private static void showDaemonError(@NonNull Handler handler, UIContext ui, Exception e) {
        Log.e(TAG, "Daemon request failed", e);
        handler.post(() -> {
            if (!ui.isAlive()) return;
            Toast.makeText(ui.getContext(), R.string.vm_daemon_error, LENGTH_LONG).show();
        });
    }
}
