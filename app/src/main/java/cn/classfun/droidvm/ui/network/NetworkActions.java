// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.network;

import static android.widget.Toast.LENGTH_LONG;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.app.Activity;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import org.json.JSONObject;

import java.util.ArrayList;
import java.io.IOException;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.NetworkConfigClient;
import cn.classfun.droidvm.lib.daemon.VmConfigClient;
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
    private static final Set<UUID> deleting = ConcurrentHashMap.newKeySet();

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
        final UUID id = config.getId();
        runOnPool(() -> {
            try {
                var client = new NetworkConfigClient(request -> DaemonConnection.getInstance().request(request));
                if (client.supported()) {
                    client.start(id.toString());
                    mainHandler.post(() -> { if (ui.isAlive()) onStarted.run(); });
                } else mainHandler.post(() -> {
                    if (ui.isAlive()) createAndStartLegacy(config, mainHandler, ui, onStarted);
                });
            } catch (Exception error) { showDaemonError(mainHandler, ui, error); }
        });
    }

    private static void createAndStartLegacy(
        @NonNull NetworkConfig config, @NonNull Handler mainHandler,
        @NonNull UIContext ui, @NonNull Runnable onStarted
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

    private static boolean alive(UIContext ui) {
        if (!ui.isAlive()) return false;
        if (!(ui.getContext() instanceof Activity)) return true;
        var activity = (Activity) ui.getContext();
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void showDeletionDialog(UIContext ui, MaterialAlertDialogBuilder builder, Runnable dismissed) {
        var dialog = builder.create();
        LifecycleOwner owner = ui.getContext() instanceof LifecycleOwner ? (LifecycleOwner) ui.getContext() : null;
        LifecycleEventObserver observer = (source, event) -> { if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss(); };
        dialog.setOnDismissListener(ignored -> {
            if (owner != null) owner.getLifecycle().removeObserver(observer);
            dismissed.run();
        });
        if (owner != null) owner.getLifecycle().addObserver(observer);
        dialog.show();
    }

    /** One confirmation/commit per network across both list and detail screens. */
    public static void confirmDeleteNetwork(@NonNull NetworkConfig config, @NonNull Handler handler,
            @NonNull UIContext ui, @NonNull NetworkStore store, @NonNull Callback callback) {
        UUID id = config.getId();
        if (!deleting.add(id)) return;
        var client = new NetworkConfigClient(request -> DaemonConnection.getInstance().request(request));
        runOnPool(() -> {
            try {
                var session = client.supported() ? client.deleteSession(id.toString()) : null;
                String name = session == null ? config.getName() : session.configuration().getString("name");
                handler.post(() -> {
                    if (!alive(ui)) { deleting.remove(id); return; }
                    var accepted = new AtomicBoolean();
                    showDeletionDialog(ui, new MaterialAlertDialogBuilder(ui.getContext()).setTitle(name).setMessage(R.string.network_delete_confirm)
                        .setPositiveButton(R.string.network_delete, (dialog, which) -> {
                            accepted.set(true);
                            if (!alive(ui)) { deleting.remove(id); return; }
                            if (session == null) {
                                deleteNetworkLegacy(ui.getContext().getApplicationContext(), handler, id, store, success -> {
                                    deleting.remove(id);
                                    if (alive(ui)) callback.onFinished(success);
                                });
                            } else runOnPool(() -> {
                                try {
                                    session.delete();
                                    finishDeletion(handler, ui, id, store, callback, true);
                                } catch (Exception error) { deletionFailed(handler, ui, id, store, callback, client, error); }
                            });
                        }).setNegativeButton(android.R.string.cancel, null), () -> { if (!accepted.get()) deleting.remove(id); });
                });
            } catch (Exception error) { deletionFailed(handler, ui, id, store, callback, client, error); }
        });
    }

    private static void finishDeletion(Handler handler, UIContext ui, UUID id, NetworkStore store, Callback callback, boolean success) {
        handler.post(() -> {
            deleting.remove(id);
            if (success) store.removeById(id);
            if (alive(ui)) callback.onFinished(success);
        });
    }

    private static void deletionFailed(Handler handler, UIContext ui, UUID id, NetworkStore store,
            Callback callback, NetworkConfigClient client, Exception error) {
        Log.e(TAG, "Network deletion requires fresh state", error);
        boolean recoverable = error instanceof VmConfigClient.CommitUnknown
            || (error instanceof VmConfigClient.RemoteFailure
                && ("COMMIT_UNCERTAIN".equals(((VmConfigClient.RemoteFailure) error).code)
                    || "UNAVAILABLE".equals(((VmConfigClient.RemoteFailure) error).code)));
        handler.post(() -> {
            if (!alive(ui)) { deleting.remove(id); return; }
            if (!recoverable) {
                deleting.remove(id);
                Toast.makeText(ui.getContext(), error.getMessage(), LENGTH_LONG).show();
                callback.onFinished(false);
                return;
            }
            var accepted = new AtomicBoolean();
            showDeletionDialog(ui, new MaterialAlertDialogBuilder(ui.getContext()).setMessage(R.string.network_delete_recovery_needed)
                .setPositiveButton(R.string.network_delete_recover, (dialog, which) -> {
                    accepted.set(true);
                    runOnPool(() -> {
                        try {
                            client.recoverConfiguration();
                            boolean present = false;
                            var networks = client.planningDocument().getJSONArray("networks");
                            for (int i = 0; i < networks.length(); i++)
                                if (id.equals(UUID.fromString(networks.getJSONObject(i).getString("id")))) { present = true; break; }
                            finishDeletion(handler, ui, id, store, callback, !present);
                        } catch (Exception failure) {
                            Log.e(TAG, "Configuration recovery failed", failure);
                            handler.post(() -> {
                                deleting.remove(id);
                                if (alive(ui)) {
                                    Toast.makeText(ui.getContext(), failure.getMessage(), LENGTH_LONG).show();
                                    callback.onFinished(false);
                                }
                            });
                        }
                    });
                }).setNegativeButton(android.R.string.cancel, null), () -> {
                    if (!accepted.get()) { deleting.remove(id); if (alive(ui)) callback.onFinished(false); }
                });
        });
    }

    private static void deleteNetworkLegacy(
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
