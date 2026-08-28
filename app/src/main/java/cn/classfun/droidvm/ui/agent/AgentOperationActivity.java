// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGHUP;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcess;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleep;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.DroidVMApp;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.ForegroundCallback;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalSessionClient;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalViewClient;
import cn.classfun.droidvm.lib.ui.termux.TerminalFonts;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.BaseAction;
import cn.classfun.droidvm.ui.vm.console.VMConsoleActivity;

/** Runs a maintenance action in the bundled initramfs under QEMU TCG. */
public final class AgentOperationActivity extends AppCompatActivity
    implements DaemonConnection.EventListener, ForegroundCallback {
    private static final String TAG = "AgentOperationActivity";
    public static final String EXTRA_AGENT_VM_JSON = "agent_vm_json";
    public static final String EXTRA_AUTOFINISH_ON_SUCCESS = "autofinish_on_success";
    private static final String AGENT_MARKER = "__DROIDVM_AGENT__:";
    private static final String READY_MARKER = AGENT_MARKER + "READY";
    private static final String RESULT_OK_MARKER = AGENT_MARKER + "RESULT:OK";
    private static final String RESULT_ERROR_MARKER = AGENT_MARKER + "RESULT:ERROR:";
    private static final int AGENT_BUFFER_LIMIT = 64 * 1024;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder agentOutput = new StringBuilder();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);
    private ProgressBar progressSpinner;
    private ImageView ivStatus;
    private TextView tvTitle;
    private TextView tvStatus;
    private TerminalView terminalView;
    private TerminalSession terminalSession;
    private MaterialButton btnCancel;
    private MaterialButton btnLogs;
    private MaterialToolbar toolbar;
    private volatile boolean bootstrapSent = false;
    private volatile boolean actionSent = false;
    private volatile boolean resultShown = false;
    private volatile boolean shellStarted = false;
    private volatile boolean actionSkipped = false;
    private volatile boolean closing = false;
    private volatile boolean activityDone = false;
    private volatile boolean vmExited = false;
    private boolean autoFinishOnSuccess = false;
    private String vmId = null;
    private String vmName = null;
    private AgentVM agentVM = null;
    private String actionPayload = null;

    private final SimpleTerminalSessionClient sessionClient = new SimpleTerminalSessionClient(this) {
        @Override
        public void onTextChanged(@NonNull TerminalSession s) {
            mainHandler.post(() -> {
                if (terminalView != null) terminalView.onScreenUpdated();
            });
        }
    };

    private final SimpleTerminalViewClient viewClient = new SimpleTerminalViewClient() {
    };

    @NonNull
    public static Intent createIntent(
        @NonNull Context context,
        @NonNull AgentVM agentVM
    ) {
        var intent = new Intent(context, AgentOperationActivity.class);
        try {
            intent.putExtra(EXTRA_AGENT_VM_JSON, agentVM.toJson().toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize AgentVM", e);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_operation);
        toolbar = findViewById(R.id.toolbar);
        progressSpinner = findViewById(R.id.progress_spinner);
        ivStatus = findViewById(R.id.iv_status);
        tvTitle = findViewById(R.id.tv_title);
        tvStatus = findViewById(R.id.tv_status);
        terminalView = findViewById(R.id.terminal_view);
        btnCancel = findViewById(R.id.btn_cancel);
        btnLogs = findViewById(R.id.btn_logs);
        btnCancel.setOnClickListener(v -> confirmCancel());
        btnLogs.setOnClickListener(v -> openLogs());
        initialize();
    }

    private void initialize() {
        toolbar.setTitle(R.string.agent_operation_title);
        toolbar.setNavigationOnClickListener(v -> confirmFinish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmFinish();
            }
        });
        var intent = getIntent();
        autoFinishOnSuccess = intent.getBooleanExtra(EXTRA_AUTOFINISH_ON_SUCCESS, false);
        var agentVmJson = intent.getStringExtra(EXTRA_AGENT_VM_JSON);
        if (agentVmJson == null) {
            Log.e(TAG, "Missing agent_vm_json extra");
            finish();
            return;
        }
        try {
            var diskStore = new DiskStore();
            diskStore.load(this);
            agentVM = new AgentVM(diskStore, new JSONObject(agentVmJson));
            var actions = BaseAction.createActions(agentVM);
            var script = BaseAction.buildRescueScript(actions);
            for (var action : actions) action.clearSecrets();
            actionPayload = Base64.encodeToString(
                script.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            // Do not retain the JSON copy containing the password for the Activity lifetime.
            intent.removeExtra(EXTRA_AGENT_VM_JSON);
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare AgentVM action", e);
            finish();
            return;
        }
        terminalView.setTerminalViewClient(viewClient);
        initTerminal();
        tvTitle.setText(R.string.agent_operation_title);
        tvStatus.setText(R.string.agent_operation_preparing);
        appendLog(getString(R.string.agent_operation_log_preparing));
        runOnPool(this::startAgent);
    }

    private void initTerminal() {
        var shell = findExecute("sh");
        var cwd = getFilesDir().getAbsolutePath();
        var args = new String[]{"sh", "-c", "while true; do sleep 86400; done"};
        var env = new String[]{
            "TERM=xterm-256color",
            "PATH=/system/bin",
            fmt("HOME=%s", cwd),
        };
        terminalSession = new TerminalSession(shell, cwd, args, env, null, sessionClient);
        float density = getResources().getDisplayMetrics().density;
        terminalView.setTextSize((int) (4 * density));
        TerminalFonts.apply(terminalView);
        terminalView.attachSession(terminalSession);
    }

    private void appendLog(@NonNull String text) {
        if (terminalSession == null) return;
        var emulator = terminalSession.getEmulator();
        if (emulator == null) return;
        if (text.contains("\n") && !text.contains("\r"))
            text = text.replace("\n", "\r\n");
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        terminalView.onScreenUpdated();
    }

    private void startAgent() {
        runOnUiThread(() -> {
            tvStatus.setText(R.string.agent_operation_creating_vm);
            appendLog(getString(R.string.agent_operation_log_creating_vm));
        });
        var vmConfig = agentVM.buildVM();
        vmName = vmConfig.getName();
        var conn = DaemonConnection.getInstance();
        try {
            registerEventListeners();
            var createReq = new JSONObject();
            createReq.put("command", "vm_create");
            createReq.put("config", vmConfig.toJson());
            var createResp = conn.request(createReq);
            if (!createResp.optBoolean("success", false)) {
                var msg = createResp.optString("message", "unknown error");
                throw new RuntimeException(fmt("vm_create failed: %s", msg));
            }
            vmId = createResp.optString("vm_id", "");
            if (vmId.isEmpty()) throw new RuntimeException("vm_create returned empty vm_id");
            runOnUiThread(() -> {
                tvStatus.setText(R.string.agent_operation_starting_vm);
                appendLog(getString(R.string.agent_operation_log_starting_vm));
            });
            var startReq = new JSONObject();
            startReq.put("command", "vm_start");
            startReq.put("vm_id", vmId);
            var startResp = conn.request(startReq);
            if (!startResp.optBoolean("success", false)) {
                var msg = startResp.optString("message", "unknown error");
                throw new RuntimeException(fmt("vm_start failed: %s", msg));
            }
            runOnUiThread(() -> {
                tvStatus.setText(R.string.agent_operation_running);
                appendLog(getString(R.string.agent_operation_log_running));
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to create/start agent VM", e);
            runOnUiThread(() -> showFailed(
                getString(R.string.agent_operation_start_failed, e.getMessage()), false));
        }
    }

    private void registerEventListeners() {
        DaemonConnection.getInstance().addListener(this);
        var app = (DroidVMApp) getApplication();
        app.getVMEventHandler().addForegroundCallback(TAG, this);
    }

    private void unregisterEventListeners() {
        DaemonConnection.getInstance().removeListener(this);
        var app = (DroidVMApp) getApplication();
        app.getVMEventHandler().removeForegroundCallback(TAG);
    }

    @Override
    public void onDaemonEvent(@NonNull JSONObject msg) {
        var type = msg.optString("type", "");
        if (!type.equals("event")) return;
        var data = msg.optJSONObject("data");
        if (data == null) return;
        var eventVmId = data.optString("vm_id", "");
        if (!eventVmId.equals(vmId)) return;
        var event = data.optString("event", "");
        if (event.equals("output")) {
            var text = URLDecoder.decode(data.optString("data", ""), StandardCharsets.UTF_8);
            var stream = data.optString("stream", "");
            if (text.isEmpty()) return;
            if (stream.equals("uart"))
                mainHandler.post(() -> appendLog(text));
            else if (stream.equals("agent"))
                handleAgentOutput(text);
        } else if (event.equals("exited")) {
            int exitCode = data.optInt("exit_code", -1);
            mainHandler.post(() -> onVMFinished(exitCode));
        }
    }

    private void handleAgentOutput(@NonNull String text) {
        String snapshot;
        synchronized (agentOutput) {
            agentOutput.append(text);
            if (agentOutput.length() > AGENT_BUFFER_LIMIT)
                agentOutput.delete(0, agentOutput.length() - AGENT_BUFFER_LIMIT);
            snapshot = agentOutput.toString();
        }
        if (snapshot.contains(AGENT_MARKER + "ACTION:SKIPPED:"))
            actionSkipped = true;
        if (!bootstrapSent && (snapshot.contains("~ #")
            || snapshot.contains("Run /bin/sh as init process"))) {
            bootstrapSent = true;
            sendAgentCommand("stty -echo 2>/dev/null; "
                + "mount -t proc proc /proc 2>/dev/null || true; "
                + "mount -t sysfs sysfs /sys 2>/dev/null || true; "
                + "mount -t devtmpfs devtmpfs /dev 2>/dev/null || true; "
                + "busybox mdev -s; printf '\\n" + READY_MARKER + "\\n'", true);
        }
        if (bootstrapSent && !actionSent && snapshot.contains(READY_MARKER)) {
            actionSent = true;
            var payload = actionPayload;
            actionPayload = null;
            if (payload == null) {
                mainHandler.post(() -> showFailed(
                    getString(R.string.agent_operation_prepare_failed), true));
                return;
            }
            var command = "printf '%s' '" + payload
                + "' | busybox base64 -d > /run/droidvm-rescue.sh && "
                + "busybox sh /run/droidvm-rescue.sh; rc=$?; "
                + "rm -f /run/droidvm-rescue.sh; "
                + "[ $rc -eq 0 ] || printf '\\n" + RESULT_ERROR_MARKER
                + "SCRIPT_FAILED\\n'";
            sendAgentCommand(command, true);
        }
        if (!resultShown && snapshot.contains(RESULT_OK_MARKER)) {
            mainHandler.post(this::showSuccess);
            return;
        }
        if (!resultShown && snapshot.contains(RESULT_ERROR_MARKER)) {
            var start = snapshot.lastIndexOf(RESULT_ERROR_MARKER) + RESULT_ERROR_MARKER.length();
            var end = snapshot.indexOf('\n', start);
            if (end < 0) end = snapshot.length();
            var code = snapshot.substring(start, end).replace("\r", "").trim();
            mainHandler.post(() -> showFailed(describeAgentError(code), true));
        }
    }

    private void sendAgentCommand(@NonNull String command, boolean failOnError) {
        runOnPool(() -> {
            try {
                writeConsole("agent", command + "\n");
            } catch (Exception e) {
                Log.e(TAG, "Failed to write agent console", e);
                if (failOnError) mainHandler.post(() -> showFailed(
                    getString(R.string.agent_operation_control_failed), false));
            }
        });
    }

    private void writeConsole(@NonNull String stream, @NonNull String data) throws Exception {
        if (vmId == null || vmId.isEmpty()) throw new IllegalStateException("VM is not ready");
        var req = new JSONObject();
        req.put("command", "vm_console_write");
        req.put("vm_id", vmId);
        req.put("stream", stream);
        req.put("data", data);
        var resp = DaemonConnection.getInstance().request(req);
        if (!resp.optBoolean("success", false))
            throw new RuntimeException(resp.optString("message", "console write failed"));
    }

    @NonNull
    private String describeAgentError(@NonNull String code) {
        switch (code) {
            case "ROOT_NOT_FOUND":
                return getString(R.string.agent_operation_error_root_not_found);
            case "PASSWD_FAILED":
                return getString(R.string.agent_operation_error_password);
            case "UNMOUNT_FAILED":
                return getString(R.string.agent_operation_error_unmount);
            case "SCRIPT_FAILED":
                return getString(R.string.agent_operation_error_script);
            case "AUTOGROW_DISK_NOT_FOUND":
            case "AUTOGROW_PROBE_FAILED":
                return getString(R.string.agent_operation_error_autogrow_disk);
            case "AUTOGROW_PARTITION_IN_USE":
                return getString(R.string.agent_operation_error_autogrow_in_use);
            case "PARTITION_GROW_FAILED":
                return getString(R.string.agent_operation_error_partition_grow);
            case "PARTITION_REREAD_FAILED":
                return getString(R.string.agent_operation_error_partition_reread);
            case "FILESYSTEM_CHECK_FAILED":
                return getString(R.string.agent_operation_error_filesystem_check);
            case "FILESYSTEM_MOUNT_FAILED":
                return getString(R.string.agent_operation_error_filesystem_mount);
            case "FILESYSTEM_GROW_FAILED":
                return getString(R.string.agent_operation_error_filesystem_grow);
            case "FILESYSTEM_UNMOUNT_FAILED":
                return getString(R.string.agent_operation_error_filesystem_unmount);
            default:
                return getString(R.string.agent_operation_error_unknown, code);
        }
    }

    private void showSuccess() {
        if (resultShown || closing) return;
        resultShown = true;
        progressSpinner.setVisibility(GONE);
        ivStatus.setVisibility(VISIBLE);
        ivStatus.setImageResource(R.drawable.ic_large_success);
        tvStatus.setText(actionSkipped
            ? R.string.agent_operation_success_skipped
            : R.string.agent_operation_success);
        appendLog(getString(actionSkipped
            ? R.string.agent_operation_log_skipped
            : R.string.agent_operation_log_success));
        if (autoFinishOnSuccess) finishAgent(true);
        else showResultButtons();
    }

    private void showFailed(@NonNull String message, boolean logsAvailable) {
        if (resultShown || closing) return;
        resultShown = true;
        progressSpinner.setVisibility(GONE);
        ivStatus.setVisibility(VISIBLE);
        ivStatus.setImageResource(R.drawable.ic_large_error);
        tvStatus.setText(getString(R.string.agent_operation_failed_detail, message));
        appendLog(getString(R.string.agent_operation_log_failed));
        btnLogs.setVisibility(logsAvailable && !vmExited ? VISIBLE : GONE);
        btnCancel.setText(android.R.string.ok);
        btnCancel.setOnClickListener(v -> finishAgent());
    }

    private void showResultButtons() {
        btnLogs.setVisibility(vmExited ? GONE : VISIBLE);
        btnCancel.setText(android.R.string.ok);
        btnCancel.setOnClickListener(v -> finishAgent());
    }

    private void openLogs() {
        if (vmExited || vmId == null || closing) return;
        btnLogs.setEnabled(false);
        runOnPool(() -> {
            try {
                if (!shellStarted) {
                    writeConsole("agent", "ROOT_DEVICE=$(cat /run/droidvm-root-device 2>/dev/null); "
                        + "if [ -n \"$ROOT_DEVICE\" ] && ! mountpoint -q /mnt; then "
                        + "mount -o rw \"$ROOT_DEVICE\" /mnt >/dev/null 2>&1 || true; fi; "
                        + "printf '\\nDroidVM rescue shell; target root: /mnt\\n' > /dev/ttyAMA0; "
                        + "setsid sh -c 'exec sh -i </dev/ttyAMA0 >/dev/ttyAMA0 2>&1' &\n");
                    shellStarted = true;
                }
                runOnUiThread(() -> {
                    btnLogs.setEnabled(true);
                    var intent = new Intent(this, VMConsoleActivity.class);
                    intent.putExtra(VMConsoleActivity.EXTRA_VM_ID, vmId);
                    intent.putExtra(VMConsoleActivity.EXTRA_VM_NAME, vmName);
                    intent.putExtra(VMConsoleActivity.EXTRA_STREAM, "uart");
                    intent.putExtra(VMConsoleActivity.EXTRA_LOGS, false);
                    startActivity(intent);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to open rescue shell", e);
                runOnUiThread(() -> {
                    btnLogs.setEnabled(true);
                    showFailed(getString(R.string.agent_operation_control_failed), false);
                });
            }
        });
    }

    private void finishAgent() {
        finishAgent(false);
    }

    private void finishAgent(boolean returnSuccess) {
        if (closing) return;
        closing = true;
        btnCancel.setEnabled(false);
        btnLogs.setEnabled(false);
        tvStatus.setText(R.string.agent_operation_stopping);
        runOnPool(() -> {
            if (!vmExited) {
                try {
                    writeConsole("agent", "sync; umount /mnt >/dev/null 2>&1 || "
                        + "umount -l /mnt >/dev/null 2>&1 || true; poweroff -f\n");
                } catch (Exception e) {
                    Log.w(TAG, "Guest shutdown command failed", e);
                }
                threadSleep(800);
                requestStop();
            }
            cleanupVM();
            runOnUiThread(() -> finishActivity(returnSuccess));
        });
    }

    private void requestStop() {
        if (vmId == null || vmId.isEmpty()) return;
        try {
            var req = new JSONObject();
            req.put("command", "vm_stop");
            req.put("vm_id", vmId);
            DaemonConnection.getInstance().request(req);
        } catch (Exception e) {
            Log.d(TAG, "VM was already stopped or stop request failed", e);
        }
    }

    private void onVMFinished(int exitCode) {
        if (activityDone) return;
        vmExited = true;
        appendLog(fmt(
            "\n--- %s (exit code: %d) ---\n",
            getString(R.string.agent_operation_vm_exited), exitCode
        ));
        btnLogs.setVisibility(GONE);
        if (closing) return;
        if (resultShown) {
            tvStatus.setText(R.string.agent_operation_vm_stopped);
            return;
        }
        showFailed(getString(R.string.agent_operation_failed, exitCode), false);
    }

    @Override
    public void onDaemonConnected() {
    }

    @Override
    public void onDaemonDisconnected() {
        if (!activityDone && !closing)
            mainHandler.post(() -> showFailed(
                getString(R.string.agent_operation_daemon_disconnected), false));
    }

    private void killTerminalSession() {
        if (terminalSession == null) return;
        try {
            if (terminalSession.isRunning())
                shellKillProcess(terminalSession.getPid(), SIGHUP);
        } catch (Exception ignored) {
        }
        terminalSession.finishIfRunning();
        terminalSession = null;
    }

    private void cleanupVM() {
        if (!cleanupStarted.compareAndSet(false, true)) return;
        unregisterEventListeners();
        var id = vmId;
        if (id == null || id.isEmpty()) return;
        requestStop();
        var conn = DaemonConnection.getInstance();
        boolean stopped = false;
        for (int i = 0; i < 50; i++) {
            try {
                var statusReq = new JSONObject();
                statusReq.put("command", "vm_status");
                statusReq.put("vm_id", id);
                var status = conn.request(statusReq);
                if (status.optBoolean("success", false)
                    && status.optString("state", "").equals("stopped")) {
                    stopped = true;
                    break;
                }
            } catch (Exception e) {
                break;
            }
            threadSleep(100);
        }
        if (!stopped) {
            Log.w(TAG, fmt("Temporary VM %s did not stop; leaving its daemon record intact", id));
            return;
        }
        try {
            var destroyReq = new JSONObject();
            destroyReq.put("command", "vm_delete");
            destroyReq.put("vm_id", id);
            var response = conn.request(destroyReq);
            if (!response.optBoolean("success", false))
                Log.w(TAG, fmt("Failed to destroy temporary VM %s: %s",
                    id, response.optString("message", "unknown error")));
            else
                Log.i(TAG, fmt("Temporary VM %s destroyed", id));
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to destroy temporary VM %s", id), e);
        }
        vmId = null;
    }

    private void finishActivity(boolean returnSuccess) {
        if (activityDone) return;
        activityDone = true;
        killTerminalSession();
        if (returnSuccess) setResult(RESULT_OK);
        finish();
    }

    private void confirmCancel() {
        if (resultShown) {
            finishAgent();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_operation_cancel_title)
            .setMessage(R.string.agent_operation_cancel_message)
            .setPositiveButton(android.R.string.ok, (d, w) -> finishAgent())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmFinish() {
        confirmCancel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!activityDone) {
            closing = true;
            runOnPool(() -> {
                cleanupVM();
                killTerminalSession();
            });
        }
    }
}
