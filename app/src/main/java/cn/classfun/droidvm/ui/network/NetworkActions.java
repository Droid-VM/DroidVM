// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.network;

import static android.widget.Toast.LENGTH_LONG;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.ArrayList;
import java.io.IOException;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.base.VmConfigOwnership;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.ui.vm.VmRunningQuery;

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
        runOnPool(() -> {
            try {
                // Until cross-resource deletion is available, never let this legacy
                // workflow mutate a configuration owned by the native service.
                VmConfigOwnership.legacyWrite(context.getFilesDir(), () -> {
                    var vmStore = new VMStore();
                    if (!vmStore.load(context)) throw new IOException("Cannot read VM configuration");
                    var runningNames = VmRunningQuery.inUseAmong(
                        namesUsingNetwork(vmStore, netId));
                    if (!runningNames.isEmpty())
                        throw new IOException(context.getString(R.string.network_delete_in_use,
                            String.join(", ", runningNames)));
                    boolean modified = false;
                    var allVms = new ArrayList<VMConfig>();
                    vmStore.forEach((id, cfg) -> allVms.add(cfg));
                    for (var vm : allVms) {
                        var nets = vm.item.opt("networks", DataItem.newArray());
                        for (int i = nets.size() - 1; i >= 0; i--) {
                            if (netId.equals(nets.get(i).optString("network_id", ""))) {
                                nets.remove(i);
                                modified = true;
                            }
                        }
                    }
                    if (modified && !vmStore.save(context))
                        throw new IOException("Cannot save VM network references");
                });
                var response = DaemonConnection.getInstance().request(new JSONObject()
                    .put("command", "network_delete").put("network_id", netId));
                if (response == null || !Boolean.TRUE.equals(response.opt("success")))
                    throw new IOException(response == null ? "Missing network deletion response"
                        : response.optString("message", "Network deletion was not confirmed"));
                // Re-read after the daemon response: native deletion also persists the
                // network list. Never write the UI's potentially stale list back.
                var fresh = new NetworkStore();
                if (!fresh.load(context)) throw new IOException("Cannot reload network configuration");
                if (fresh.findById(networkId) != null) {
                    fresh.removeById(networkId);
                    if (!fresh.save(context)) throw new IOException("Cannot save network configuration");
                }
                mainHandler.post(() -> {
                    netStore.removeById(networkId);
                    callback.onFinished(true);
                });
            } catch (Exception error) {
                Log.e(TAG, "Network deletion incomplete; reload state before retrying", error);
                mainHandler.post(() -> {
                    Toast.makeText(context, error.getMessage(), LENGTH_LONG).show();
                    callback.onFinished(false);
                });
            }
        });
    }

    static ArrayList<String> namesUsingNetwork(VMStore store, String networkId) {
        var names = new ArrayList<String>();
        store.forEach((id, vm) -> {
            for (var entry : vm.item.opt("networks", DataItem.newArray())) {
                if (networkId.equals(entry.getValue().optString("network_id", ""))) {
                    names.add(vm.getName());
                    break;
                }
            }
        });
        return names;
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
