package cn.classfun.droidvm.ui.vm;

import static android.widget.Toast.LENGTH_LONG;
import static cn.classfun.droidvm.ui.main.settings.MainSettingsFragment.isAutoConsoleEnabled;

import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.ui.UIContext;

public final class VMActions {
    private static final String TAG = "VMActions";

    private VMActions() {
    }

    public static void createAndStart(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull AtomicBoolean wantOpenConsole
    ) {
        var conn = DaemonConnection.getInstance();
        var createReq = conn.buildRequest("vm_exists");
        createReq.put("vm_id", config.getId().toString());
        DaemonConnection.OnError err = e ->
            showDaemonError(mainHandler, ui, e);
        DaemonConnection.OnUnsuccessful f = resp ->
            showError(mainHandler, ui, resp.optString("message", "Unknown error"));
        DaemonConnection.OnResponse onStart = resp -> {
            if (isAutoConsoleEnabled(ui.getContext()))
                wantOpenConsole.set(true);
        };
        DaemonConnection.OnResponse onCreateModify = resp -> conn
            .buildRequest("vm_start")
            .copy(resp, "vm_id")
            .onResponse(onStart)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
        DaemonConnection.OnResponse onExists = resp -> {
            var exists = resp.optBoolean("exists", false);
            conn.buildRequest(exists ? "vm_modify" : "vm_create")
                .put("config", config)
                .onResponse(onCreateModify)
                .onUnsuccessful(f)
                .onError(err)
                .invoke();
        };
        conn.buildRequest("vm_exists")
            .put("vm_id", config.getId())
            .onResponse(onExists)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    public static void sendCommand(
        @NonNull String command,
        @NonNull UUID vmId,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui
    ) {
        sendCommand(command, vmId, mainHandler, ui, null);
    }

    public static void sendCommand(
        @NonNull String command,
        @NonNull UUID vmId,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @Nullable Runnable onSuccess
    ) {
        DaemonConnection.OnError err = e ->
            showDaemonError(mainHandler, ui, e);
        DaemonConnection.OnUnsuccessful f = resp ->
            showError(mainHandler, ui, resp.optString("message", "Unknown error"));
        DaemonConnection.getInstance().buildRequest(command)
            .put("vm_id", vmId.toString())
            .onResponse(resp -> {
                if (onSuccess != null) onSuccess.run();
            })
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    public static void sendControlCommand(
        @NonNull String cmd,
        @NonNull UUID vmId,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui
    ) {
        DaemonConnection.OnError err = e ->
            showDaemonError(mainHandler, ui, e);
        DaemonConnection.OnUnsuccessful f = resp ->
            showError(mainHandler, ui, resp.optString("message", "Unknown error"));
        DaemonConnection.getInstance().buildRequest("vm_control")
            .put("vm_id", vmId.toString())
            .put("cmd", cmd)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    private static void showError(@NonNull Handler handler, UIContext ui, String msg) {
        handler.post(() -> {
            if (ui.isAlive())
                Toast.makeText(ui.getContext(), msg, LENGTH_LONG).show();
        });
    }

    private static void showDaemonError(@NonNull Handler handler, UIContext ui, Exception e) {
        Log.e(TAG, "Daemon request failed", e);
        handler.post(() -> {
            if (ui.isAlive())
                Toast.makeText(ui.getContext(), R.string.vm_daemon_error, LENGTH_LONG).show();
        });
    }
}
