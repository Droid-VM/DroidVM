package cn.classfun.droidvm.lib.daemon;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.FileUtils.calcHashForFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.ui.SplashActivity;
import cn.classfun.droidvm.ui.main.MainActivity;

public final class VMEventHandler implements
    DaemonConnection.EventListener,
    Application.ActivityLifecycleCallbacks,
    ForegroundCallback {
    private static final String TAG = "VMEventHandler";
    private static final String CHANNEL_ID = "vm_events";
    private static final int NOTIFY_ID_BASE = 20000;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HashMap<String, ForegroundCallback> foregroundCallback = new HashMap<>();
    private final List<ActivityTaskInfo> pendingTasks = new ArrayList<>();
    private int activeActivityCount = 0;
    private WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private volatile boolean hashMismatchShown = false;

    public interface ActivityTask {
        void run(@NonNull Activity activity);
    }

    private static class ActivityTaskInfo {
        public ActivityTask task;
        public List<Class<? extends Activity>> avoidClass;
    }

    public VMEventHandler(@NonNull Context appContext) {
        this.appContext = appContext.getApplicationContext();
        createNotificationChannel();
        addExitHandler();
    }

    public void addForegroundCallback(@NonNull String id, @NonNull ForegroundCallback cb) {
        foregroundCallback.put(id, cb);
    }

    public void removeForegroundCallback(@NonNull String id) {
        foregroundCallback.remove(id);
    }

    public boolean isAppInForeground() {
        return activeActivityCount > 0;
    }

    private void callOnVmExited(UUID vmId, String vmName, int exitCode, JSONObject data) {
        for (var c : foregroundCallback.values())
            c.onVMExited(vmId, vmName, exitCode, data);
    }

    private void showExitDialog(Activity act, String vmName, int exitCode, @NonNull JSONObject data) {
        var logText = new StringBuilder();
        var stdio = data.optString("stdio", "");
        if (!stdio.isEmpty()) {
            logText.append(stdio);
        } else {
            logText.append(act.getString(R.string.vm_exit_no_logs));
        }
        var store = new VMStore();
        store.load(act);
        var vm = store.findByName(vmName);
        if (vm != null && vm.item.optBoolean("temporary", false)) return;
        var inf = LayoutInflater.from(act);
        var view = inf.inflate(R.layout.dialog_logs, null);
        TextView tvLog = view.findViewById(R.id.tv_log);
        tvLog.setText(logText.toString().trim());
        new MaterialAlertDialogBuilder(act)
            .setTitle(act.getString(R.string.vm_exit_dialog_title, vmName))
            .setMessage(act.getString(R.string.vm_exit_dialog_message, exitCode))
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void addExitHandler() {
        addForegroundCallback("exit", this);
    }

    @Override
    public void onVMExited(UUID vmId, String vmName, int exitCode, JSONObject data) {
        queueActivityTask(act -> {
            if (exitCode == 0) {
                Toast.makeText(
                    act,
                    act.getString(R.string.vm_exited_success, vmName),
                    LENGTH_SHORT
                ).show();
            } else {
                Toast.makeText(
                    act,
                    act.getString(R.string.vm_exited_error, vmName, exitCode),
                    LENGTH_LONG
                ).show();
                showExitDialog(act, vmName, exitCode, data);
            }
        }, SplashActivity.class);
    }

    @Override
    public void onDaemonEvent(@NonNull JSONObject msg) {
        var type = msg.optString("type", "");
        if (!type.equals("event")) return;
        var data = msg.optJSONObject("data");
        if (data == null) return;
        var event = data.optString("event", "");
        var vmId = UUID.fromString(data.optString("vm_id", ""));
        var vmName = data.optString("vm_name", "");
        var stateStr = data.optString("state", "stopped");
        var state = VMState.valueOf(stateStr.toUpperCase());
        mainHandler.post(() -> {
            for (var c : foregroundCallback.values())
                c.onVMStateChanged(vmId, state);
            if (event.equals("exited")) {
                int exitCode = data.optInt("exit_code", -1);
                if (foregroundCallback.isEmpty() || !isAppInForeground())
                    showExitNotification(vmId, vmName, exitCode);
                callOnVmExited(vmId, vmName, exitCode, data);
            }
        });
    }

    @Override
    public void onDaemonConnected() {
        Log.d(TAG, "Daemon connected");
        checkDaemonHash();
        queueActivityTask(act -> Toast.makeText(
            act, R.string.daemon_connected, Toast.LENGTH_SHORT
        ).show());
    }

    @Override
    public void onDaemonDisconnected() {
        Log.d(TAG, "Daemon disconnected");
        hashMismatchShown = false;
        queueActivityTask(act -> Toast.makeText(
            act, R.string.daemon_disconnected, Toast.LENGTH_SHORT
        ).show());
    }

    private boolean isOutdatedDaemon(JSONObject resp) {
        try {
            var daemonHash = resp.optString("daemon_hash", "");
            if (daemonHash.isEmpty()) {
                Log.w(TAG, "Daemon did not report daemon_hash");
                return true;
            }
            var localHash = calcHashForFile(appContext.getPackageCodePath(), "SHA-256");
            if (daemonHash.equals(localHash)) {
                Log.i(TAG, "Daemon hash matches app");
                return false;
            }
            Log.w(TAG, fmt("Daemon hash mismatch: daemon=%s app=%s", daemonHash, localHash));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to check daemon version", e);
            return false;
        }
    }

    private void checkDaemonHash() {
        try {
            var req = new JSONObject();
            req.put("command", "version");
            var resp = DaemonConnection.getInstance().request(req);
            if (!isOutdatedDaemon(resp)) return;
            if (hashMismatchShown) return;
            hashMismatchShown = true;
            queueActivityTask(this::showHashMismatchDialog, SplashActivity.class);
        } catch (Exception e) {
            Log.e(TAG, "Failed to check daemon hash", e);
        }
    }

    @Nullable
    private Activity getCurrentActivity(List<Class<? extends Activity>> avoidClass) {
        var activity = currentActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed())
            return null;
        if (avoidClass != null)
            for (var cls : avoidClass)
                if (cls.isInstance(activity))
                    return null;
        return activity;
    }

    private void showHashMismatchDialog(Activity activity) {
        DialogInterface.OnClickListener onIgnore = (dialog, which) -> dialog.dismiss();
        DialogInterface.OnClickListener onRestart = (dialog, which) -> {
            dialog.dismiss();
            runOnPool(() -> {
                var ui = UIContext.fromActivity(activity);
                var helper = new DaemonHelper(ui);
                helper.restartDaemon();
            });
        };
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.daemon_outdated_title)
            .setMessage(R.string.daemon_outdated_message)
            .setCancelable(false)
            .setPositiveButton(R.string.daemon_outdated_restart, onRestart)
            .setNegativeButton(R.string.daemon_outdated_ignore, onIgnore)
            .show();
    }

    private void createNotificationChannel() {
        var channel = new NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.notif_channel_vm_events),
            NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(appContext.getString(R.string.notif_channel_vm_events_desc));
        var nm = appContext.getSystemService(NotificationManager.class);
        if (nm != null)
            nm.createNotificationChannel(channel);
    }

    private void showExitNotification(@NonNull UUID vmId, String vmName, int exitCode) {
        var intent = new Intent(appContext, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        var pending = PendingIntent.getActivity(
            appContext, vmId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        String title, text;
        if (exitCode == 0) {
            title = appContext.getString(R.string.notif_vm_stopped_title);
            text = appContext.getString(R.string.vm_exited_success, vmName);
        } else {
            title = appContext.getString(R.string.notif_vm_failed_title);
            text = appContext.getString(R.string.vm_exited_error, vmName, exitCode);
        }
        var builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_large_server)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true);
        var nm = appContext.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFY_ID_BASE + vmId.hashCode(), builder.build());
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        activeActivityCount++;
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        activeActivityCount--;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle s) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        currentActivity = new WeakReference<>(activity);
        runPendingTasks();
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (currentActivity.get() == activity)
            currentActivity = new WeakReference<>(null);
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle s) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    public void runPendingTasks() {
        for (var it = pendingTasks.iterator(); it.hasNext(); ) {
            try {
                var info = it.next();
                var activity = getCurrentActivity(info.avoidClass);
                if (activity == null) continue;
                info.task.run(activity);
                it.remove();
            } catch (Exception e) {
                Log.e(TAG, "Failed to run pending task", e);
            }
        }
    }

    public void queueActivityTask(ActivityTask task, List<Class<? extends Activity>> avoidClass) {
        mainHandler.post(() -> {
            var activity = getCurrentActivity(avoidClass);
            if (activity != null) {
                task.run(activity);
            } else {
                var info = new ActivityTaskInfo();
                info.task = task;
                info.avoidClass = avoidClass;
                pendingTasks.add(info);
            }
        });
    }

    public void queueActivityTask(ActivityTask task, Class<? extends Activity> avoidClass) {
        queueActivityTask(task, List.of(avoidClass));
    }

    public void queueActivityTask(ActivityTask task) {
        queueActivityTask(task, (List<Class<? extends Activity>>) null);
    }
}
