// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.agent;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGHUP;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcess;
import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
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
import cn.classfun.droidvm.lib.ui.termux.TerminalPanelView;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.BaseAction;

/** Runs a maintenance action in the bundled initramfs under QEMU TCG. */
public final class AgentOperationActivity extends AppCompatActivity
    implements DaemonConnection.EventListener, ForegroundCallback {
    private static final String TAG = "AgentOperationActivity";
    public static final String EXTRA_AGENT_VM_JSON = "agent_vm_json";
    public static final String EXTRA_AUTOFINISH_ON_SUCCESS = "autofinish_on_success";
    private static final String AGENT_MARKER = "__DROIDVM_AGENT__:";
    private static final String READY_MARKER = AGENT_MARKER + "READY"; // concat-ok: compile-time constant
    private static final String RESULT_OK_MARKER = AGENT_MARKER + "RESULT:OK"; // concat-ok: compile-time constant
    private static final String RESULT_ERROR_MARKER = AGENT_MARKER + "RESULT:ERROR:"; // concat-ok: compile-time constant
    private static final String ACTION_SKIPPED_MARKER = AGENT_MARKER + "ACTION:SKIPPED:"; // concat-ok: compile-time constant
    private static final int AGENT_BUFFER_LIMIT = 64 * 1024;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder agentOutput = new StringBuilder();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);
    private ProgressBar progressSpinner;
    private ImageView ivStatus;
    private TextView tvTitle;
    private TextView tvStatus;
    private TerminalPanelView terminalPanel;
    private TerminalSession terminalSession;
    private MaterialButton btnCancel;
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
    private AgentVM agentVM = null;
    private String actionPayload = null;

    private final SimpleTerminalSessionClient sessionClient = new SimpleTerminalSessionClient(this) {
        @Override
        public void onTextChanged(@NonNull TerminalSession s) {
            mainHandler.post(() -> {
                if (terminalPanel != null) terminalPanel.refresh();
            });
        }
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
        terminalPanel = findViewById(R.id.terminal_panel);
        terminalPanel.setInteractive(false);
        btnCancel = findViewById(R.id.btn_cancel);
        btnCancel.setOnClickListener(v -> confirmCancel());
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
        tvTitle.setText(R.string.agent_operation_title);
        tvStatus.setText(R.string.agent_operation_preparing);
        runOnPool(this::startAgent);
    }

    /** Connects the embedded panel to the rescue VM's UART without enabling input yet. */
    private void startConsoleSession() {
        stopConsoleSession();
        if (vmId == null || vmId.isEmpty() || closing || vmExited) return;
        var shell = findExecute("su", "/system/bin/su");
        var cwd = getFilesDir().getAbsolutePath();
        var command = fmt(
            "exec %s console --raw %s uart",
            escapedString(getAssetBinaryPath("droidvm")),
            escapedString(vmId)
        );
        var args = new String[]{"su", "-c", command};
        var env = new String[]{
            "TERM=xterm-256color",
            "PATH=/system/bin",
            fmt("HOME=%s", cwd),
        };
        terminalSession = new TerminalSession(shell, cwd, args, env, null, sessionClient);
        terminalPanel.attachSession(terminalSession);
    }

    private void startAgent() {
        runOnUiThread(() -> {
            tvStatus.setText(R.string.agent_operation_creating_vm);
        });
        var vmConfig = agentVM.buildVM();
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
                startConsoleSession();
                tvStatus.setText(R.string.agent_operation_running);
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
            var stream = data.optString("stream", "");
            if (!stream.equals("agent")) return;
            var text = URLDecoder.decode(data.optString("data", ""), StandardCharsets.UTF_8);
            if (text.isEmpty()) return;
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
        if (snapshot.contains(ACTION_SKIPPED_MARKER))
            actionSkipped = true;
        if (!bootstrapSent && (snapshot.contains("~ #")
            || snapshot.contains("Run /bin/sh as init process"))) {
            bootstrapSent = true;
            sendAgentCommand(fmt("stty -echo 2>/dev/null; "
                + "mount -t proc proc /proc 2>/dev/null || true; "
                + "mount -t sysfs sysfs /sys 2>/dev/null || true; "
                + "mount -t devtmpfs devtmpfs /dev 2>/dev/null || true; "
                + "busybox mdev -s; printf '\\n%s\\n'", READY_MARKER), true);
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
            var command = fmt(
                "printf '%%s' '%s' | busybox base64 -d > /run/droidvm-rescue.sh && "
                + "busybox sh /run/droidvm-rescue.sh; rc=$?; "
                + "rm -f /run/droidvm-rescue.sh; "
                + "[ $rc -eq 0 ] || printf '\\n%sSCRIPT_FAILED\\n'",
                payload, RESULT_ERROR_MARKER);
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
                writeConsole("agent", fmt("%s\n", command));
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
        if (autoFinishOnSuccess) {
            // The Linux VM creation chain must return immediately. Never start the optional
            // rescue shell on this path or wait for terminal interaction.
            finishAgent(true);
            return;
        }
        showResultButtons();
        enableInteractiveConsole();
    }

    private void showFailed(@NonNull String message, boolean logsAvailable) {
        if (resultShown || closing) return;
        resultShown = true;
        progressSpinner.setVisibility(GONE);
        ivStatus.setVisibility(VISIBLE);
        ivStatus.setImageResource(R.drawable.ic_large_error);
        tvStatus.setText(getString(R.string.agent_operation_failed_detail, message));
        btnCancel.setText(android.R.string.ok);
        btnCancel.setOnClickListener(v -> finishAgent());
        if (logsAvailable && !vmExited) enableInteractiveConsole();
    }

    private void showResultButtons() {
        btnCancel.setText(android.R.string.ok);
        btnCancel.setOnClickListener(v -> finishAgent());
    }

    /** Starts the optional UART shell only for a result page that remains on screen. */
    private void enableInteractiveConsole() {
        if (vmExited || vmId == null || closing) return;
        if (shellStarted) {
            terminalPanel.setInteractive(true);
            return;
        }
        runOnPool(() -> {
            try {
                writeConsole("agent", "ROOT_DEVICE=$(cat /run/droidvm-root-device 2>/dev/null); "
                    + "if [ -n \"$ROOT_DEVICE\" ] && ! mountpoint -q /mnt; then "
                    + "mount -o rw \"$ROOT_DEVICE\" /mnt >/dev/null 2>&1 || true; fi; "
                    + "printf '\\nDroidVM rescue shell; target root: /mnt\\n' > /dev/ttyAMA0; "
                    + "setsid sh -c 'exec sh -i </dev/ttyAMA0 >/dev/ttyAMA0 2>&1' &\n");
                shellStarted = true;
                runOnUiThread(() -> {
                    if (!closing && !vmExited) terminalPanel.setInteractive(true);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to enable rescue shell", e);
                runOnUiThread(() -> {
                    if (!closing) tvStatus.setText(getString(
                        R.string.agent_operation_failed_detail,
                        getString(R.string.agent_operation_control_failed)
                    ));
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
        terminalPanel.setInteractive(false);
        stopConsoleSession();
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
        terminalPanel.setInteractive(false);
        stopConsoleSession();
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

    private void stopConsoleSession() {
        if (terminalSession == null) return;
        var session = terminalSession;
        terminalSession = null;
        try {
            if (session.isRunning()) shellKillProcess(session.getPid(), SIGHUP);
        } catch (Exception ignored) {
        }
        session.finishIfRunning();
        terminalPanel.clearSession(session);
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
        stopConsoleSession();
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
                stopConsoleSession();
            });
        }
    }
}
