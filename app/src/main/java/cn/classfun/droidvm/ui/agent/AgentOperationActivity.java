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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.DroidVMApp;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.ForegroundCallback;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalSessionClient;
import cn.classfun.droidvm.lib.ui.termux.TerminalPanelView;
import cn.classfun.droidvm.ui.agent.base.AgentPayloadChunks;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.BaseAction;
import cn.classfun.droidvm.ui.agent.password.PasswordAction;

/** Runs maintenance actions through one visible rescue console. */
public final class AgentOperationActivity extends AppCompatActivity
    implements DaemonConnection.EventListener, ForegroundCallback {
    private static final String TAG = "AgentOperationActivity";
    public static final String EXTRA_AGENT_VM_JSON = "agent_vm_json";
    public static final String EXTRA_AUTOFINISH_ON_SUCCESS = "autofinish_on_success";
    private static final String AGENT_MARKER = "__DROIDVM_AGENT__:";
    private static final String TTY_READY_MARKER = AGENT_MARKER + "TTY:READY"; // concat-ok: compile-time constant
    private static final String READY_MARKER = AGENT_MARKER + "READY"; // concat-ok: compile-time constant
    private static final String STAGE_READY_MARKER = AGENT_MARKER + "STAGE:READY"; // concat-ok: compile-time constant
    private static final String STAGE_CHUNK_MARKER = AGENT_MARKER + "STAGE:CHUNK:"; // concat-ok: compile-time constant
    private static final String SCRIPT_READY_MARKER = AGENT_MARKER + "SCRIPT:READY"; // concat-ok: compile-time constant
    private static final String SHELL_READY_MARKER = AGENT_MARKER + "SHELL:READY"; // concat-ok: compile-time constant
    private static final String RESULT_OK_MARKER = AGENT_MARKER + "RESULT:OK"; // concat-ok: compile-time constant
    private static final String RESULT_ERROR_MARKER = AGENT_MARKER + "RESULT:ERROR:"; // concat-ok: compile-time constant
    private static final String ACTION_START_MARKER = AGENT_MARKER + "ACTION:START:"; // concat-ok: compile-time constant
    private static final String ACTION_OK_MARKER = AGENT_MARKER + "ACTION:OK:"; // concat-ok: compile-time constant
    private static final String ACTION_ERROR_MARKER = AGENT_MARKER + "ACTION:ERROR:"; // concat-ok: compile-time constant
    private static final String ACTION_SKIPPED_MARKER = AGENT_MARKER + "ACTION:SKIPPED:"; // concat-ok: compile-time constant
    private static final String[] PASSWORD_PROMPTS = new String[]{
        "New password:",
        "Re-enter new password:",
        "Retype new password:",
        "Enter new UNIX password:",
        "Retype new UNIX password:",
    };
    private static final int AGENT_BUFFER_LIMIT = 64 * 1024;
    private static final int PASSWORD_PROMPT_TAIL_LIMIT = 128;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder agentOutput = new StringBuilder();
    private final Map<Integer, String> actionPasswords = new ConcurrentHashMap<>();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);
    private ProgressBar progressSpinner;
    private ImageView ivStatus;
    private TextView tvTitle;
    private TextView tvStatus;
    private TerminalPanelView terminalPanel;
    private TerminalSession terminalSession;
    private MaterialButton btnCancel;
    private MaterialToolbar toolbar;
    private volatile boolean ttyShellRequested = false;
    private volatile boolean bootstrapSent = false;
    private volatile boolean payloadStageStarted = false;
    private volatile boolean payloadDecodeSent = false;
    private volatile boolean actionSent = false;
    private volatile boolean resultShown = false;
    private volatile boolean handoffRequested = false;
    private volatile boolean handoffComplete = false;
    private volatile boolean actionSkipped = false;
    private volatile boolean closing = false;
    private volatile boolean activityDone = false;
    private volatile boolean vmExited = false;
    private boolean autoFinishOnSuccess = false;
    private String vmId = null;
    private AgentVM agentVM = null;
    private volatile List<String> actionPayloadChunks = Collections.emptyList();
    private int nextPayloadChunk = 0;
    private String activePassword = null;
    private String passwordPromptTail = "";
    private int activeActionIndex = -1;

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
            for (int i = 0; i < actions.size(); i++)
                if (actions.get(i) instanceof PasswordAction)
                    actionPasswords.put(i, ((PasswordAction) actions.get(i)).getPassword());
            var script = BaseAction.buildRescueScript(actions);
            for (var action : actions) action.clearSecrets();
            var actionPayload = Base64.encodeToString(
                script.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            actionPayloadChunks = AgentPayloadChunks.split(actionPayload);
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

    /** Connects the embedded panel to the operation console without enabling user input yet. */
    private void startConsoleSession(@NonNull String stream) {
        stopConsoleSession();
        if (vmId == null || vmId.isEmpty() || closing || vmExited) return;
        var shell = findExecute("su", "/system/bin/su");
        var cwd = getFilesDir().getAbsolutePath();
        var command = fmt(
            "exec %s console --raw %s %s",
            escapedString(getAssetBinaryPath("droidvm")),
            escapedString(vmId),
            escapedString(stream)
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

    /** Waits for the explicitly configured console without blocking control or auto-finish. */
    private void startConsoleWhenReady() {
        var stream = agentVM.getOperationConsoleStream();
        if (stream == null) return;
        runOnPool(() -> {
            for (int i = 0; i < 50 && !closing && !vmExited; i++) {
                if (isOperationConsoleReadable(stream)) {
                    runOnUiThread(() -> {
                        if (!closing && !vmExited && terminalSession == null)
                            startConsoleSession(stream);
                    });
                    return;
                }
                threadSleep(100);
            }
            if (!closing && !vmExited)
                Log.w(TAG, "Operation console did not become readable");
        });
    }

    private boolean isOperationConsoleReadable(@NonNull String stream) {
        try {
            var infoReq = new JSONObject();
            infoReq.put("command", "vm_console_info");
            infoReq.put("vm_id", vmId);
            infoReq.put("stream", stream);
            var infoResp = DaemonConnection.getInstance().request(infoReq);
            var data = infoResp.optJSONObject("data");
            return infoResp.optBoolean("success", false)
                && data != null && data.optBoolean("readable", false);
        } catch (Exception e) {
            if (!closing && !vmExited) Log.d(TAG, "Operation console is not ready", e);
        }
        return false;
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
            runOnUiThread(() -> tvStatus.setText(R.string.agent_operation_running));
            startConsoleWhenReady();
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
            var operationStream = agentVM.getOperationConsoleStream();
            if (operationStream == null || !stream.equals(operationStream)) return;
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
        updateActivePassword(snapshot);
        handlePasswordPrompts(text);
        if (snapshot.contains(ACTION_SKIPPED_MARKER))
            actionSkipped = true;
        if (!ttyShellRequested && (snapshot.contains("~ #")
            || snapshot.contains("Run /bin/sh as init process"))) {
            ttyShellRequested = true;
            sendOperationCommand(
                "busybox setsid -c sh -c 'printf \"\\n__DROIDVM_%s:TTY:READY\\n\" "
                    + "AGENT__; exec sh'",
                true);
        }
        if (ttyShellRequested && !bootstrapSent && snapshot.contains(TTY_READY_MARKER)) {
            bootstrapSent = true;
            sendOperationCommand("stty -echo 2>/dev/null; "
                + "mount -t proc proc /proc 2>/dev/null || true; "
                + "mount -t sysfs sysfs /sys 2>/dev/null || true; "
                + "mount -t devtmpfs devtmpfs /dev 2>/dev/null || true; "
                + "busybox mdev -s; "
                + "printf '\\n__DROIDVM_%s:READY\\n' AGENT__", true);
        }
        if (bootstrapSent && !payloadStageStarted && snapshot.contains(READY_MARKER)) {
            payloadStageStarted = true;
            nextPayloadChunk = 0;
            if (actionPayloadChunks.isEmpty()) {
                mainHandler.post(() -> showFailed(
                    getString(R.string.agent_operation_prepare_failed), true));
                return;
            }
            sendOperationCommand(
                "rm -f /run/droidvm-rescue.b64 /run/droidvm-rescue.sh; "
                    + "if : > /run/droidvm-rescue.b64; then "
                    + "printf '\\n__DROIDVM_%s:STAGE:READY\\n' AGENT__; "
                    + "else stty echo; "
                    + "printf '\\n__DROIDVM_%s:RESULT:ERROR:SCRIPT_FAILED\\n' AGENT__; fi",
                true);
        }
        continuePayloadStage(snapshot);
        if (payloadDecodeSent && !actionSent && snapshot.contains(SCRIPT_READY_MARKER)) {
            actionSent = true;
            sendOperationCommand(
                "busybox sh /run/droidvm-rescue.sh; rc=$?; "
                    + "rm -f /run/droidvm-rescue.sh; "
                    + "[ $rc -eq 0 ] || "
                    + "printf '\\n__DROIDVM_%s:RESULT:ERROR:SCRIPT_FAILED\\n' AGENT__",
                true);
        }
        if (handoffRequested && !handoffComplete && snapshot.contains(SHELL_READY_MARKER)) {
            handoffComplete = true;
            mainHandler.post(() -> {
                if (!closing && !vmExited) terminalPanel.setInteractive(true);
            });
        }
        if (!resultShown && snapshot.contains(RESULT_OK_MARKER)) {
            clearPasswords();
            mainHandler.post(this::showSuccess);
            return;
        }
        if (!resultShown && snapshot.contains(RESULT_ERROR_MARKER)) {
            clearPasswords();
            var start = snapshot.lastIndexOf(RESULT_ERROR_MARKER) + RESULT_ERROR_MARKER.length();
            var end = snapshot.indexOf('\n', start);
            if (end < 0) end = snapshot.length();
            var code = snapshot.substring(start, end).replace("\r", "").trim();
            mainHandler.post(() -> showFailed(describeAgentError(code), true));
        }
    }

    private void continuePayloadStage(@NonNull String snapshot) {
        if (!payloadStageStarted || payloadDecodeSent) return;
        if (nextPayloadChunk == 0) {
            if (!snapshot.contains(STAGE_READY_MARKER)) return;
        } else {
            var expectedMarker = fmt(
                "%s%d:OK", STAGE_CHUNK_MARKER, nextPayloadChunk - 1);
            if (!snapshot.contains(expectedMarker)) return;
        }
        var chunks = actionPayloadChunks;
        if (nextPayloadChunk < chunks.size()) {
            int index = nextPayloadChunk;
            nextPayloadChunk++;
            var command = fmt(
                "if printf '%%s' '%s' >> /run/droidvm-rescue.b64; then "
                    + "printf '\\n__DROIDVM_%%s:STAGE:CHUNK:%d:OK\\n' AGENT__; "
                    + "else stty echo; "
                    + "printf '\\n__DROIDVM_%%s:RESULT:ERROR:SCRIPT_FAILED\\n' AGENT__; fi",
                chunks.get(index), index);
            sendOperationCommand(command, true);
            return;
        }
        payloadDecodeSent = true;
        actionPayloadChunks = Collections.emptyList();
        sendOperationCommand(
            "if busybox base64 -d < /run/droidvm-rescue.b64 "
                + "> /run/droidvm-rescue.sh "
                + "&& busybox sh -n /run/droidvm-rescue.sh; then "
                + "rm -f /run/droidvm-rescue.b64; stty echo; "
                + "printf '\\n__DROIDVM_%s:SCRIPT:READY\\n' AGENT__; "
                + "else rm -f /run/droidvm-rescue.b64 /run/droidvm-rescue.sh; "
                + "stty echo; "
                + "printf '\\n__DROIDVM_%s:RESULT:ERROR:SCRIPT_FAILED\\n' AGENT__; fi",
            true);
    }

    private void updateActivePassword(@NonNull String snapshot) {
        int start = snapshot.lastIndexOf(ACTION_START_MARKER);
        int finished = Math.max(snapshot.lastIndexOf(ACTION_OK_MARKER),
            Math.max(snapshot.lastIndexOf(ACTION_ERROR_MARKER),
                snapshot.lastIndexOf(ACTION_SKIPPED_MARKER)));
        if (finished > start) {
            if (activeActionIndex >= 0) actionPasswords.remove(activeActionIndex);
            activeActionIndex = -1;
            activePassword = null;
            passwordPromptTail = "";
            return;
        }
        if (start < 0) return;
        int valueStart = start + ACTION_START_MARKER.length();
        int lineEnd = snapshot.indexOf('\n', valueStart);
        if (lineEnd < 0) return;
        var marker = snapshot.substring(valueStart, lineEnd).replace("\r", "").trim();
        int separator = marker.indexOf(':');
        if (separator <= 0) return;
        try {
            int index = Integer.parseInt(marker.substring(0, separator));
            if (index == activeActionIndex) return;
            activeActionIndex = index;
            activePassword = actionPasswords.get(index);
            passwordPromptTail = "";
        } catch (NumberFormatException ignored) {
        }
    }

    private void handlePasswordPrompts(@NonNull String text) {
        var password = activePassword;
        if (password == null) return;
        passwordPromptTail = passwordPromptTail + text;
        while (true) {
            int first = -1;
            int promptLength = 0;
            for (var prompt : PASSWORD_PROMPTS) {
                int index = passwordPromptTail.indexOf(prompt);
                if (index >= 0 && (first < 0 || index < first)) {
                    first = index;
                    promptLength = prompt.length();
                }
            }
            if (first < 0) {
                if (passwordPromptTail.length() > PASSWORD_PROMPT_TAIL_LIMIT)
                    passwordPromptTail = passwordPromptTail.substring(
                        passwordPromptTail.length() - PASSWORD_PROMPT_TAIL_LIMIT);
                return;
            }
            passwordPromptTail = passwordPromptTail.substring(first + promptLength);
            sendPasswordInput(password);
        }
    }

    private void sendPasswordInput(@NonNull String password) {
        runOnPool(() -> {
            try {
                writeOperationConsole(fmt("%s\n", password));
            } catch (Exception e) {
                Log.e(TAG, "Failed to write password input", e);
                mainHandler.post(() -> showFailed(
                    getString(R.string.agent_operation_control_failed), false));
            }
        });
    }

    private void clearPasswords() {
        actionPasswords.clear();
        activeActionIndex = -1;
        activePassword = null;
        passwordPromptTail = "";
    }

    private void sendOperationCommand(@NonNull String command, boolean failOnError) {
        runOnPool(() -> {
            try {
                writeOperationConsole(fmt("%s\n", command));
            } catch (Exception e) {
                Log.e(TAG, "Failed to write operation console", e);
                if (failOnError) mainHandler.post(() -> showFailed(
                    getString(R.string.agent_operation_control_failed), false));
            }
        });
    }

    private void writeOperationConsole(@NonNull String data) throws Exception {
        var stream = agentVM.getOperationConsoleStream();
        if (stream == null) throw new IllegalStateException("Operation console is not configured");
        writeConsole(stream, data);
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
        requestConsoleHandoff();
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
        if (logsAvailable && !vmExited) requestConsoleHandoff();
    }

    private void showResultButtons() {
        btnCancel.setText(android.R.string.ok);
        btnCancel.setOnClickListener(v -> finishAgent());
    }

    /** Returns the controlling operation shell after the action script has exited. */
    private void requestConsoleHandoff() {
        if (vmExited || vmId == null || closing) return;
        if (handoffComplete) {
            terminalPanel.setInteractive(true);
            return;
        }
        if (handoffRequested) return;
        handoffRequested = true;
        sendOperationCommand(
            "ROOT_DEVICE=$(cat /run/droidvm-root-device 2>/dev/null); "
                + "if [ -n \"$ROOT_DEVICE\" ] && ! mountpoint -q /mnt; then "
                + "mount -o rw \"$ROOT_DEVICE\" /mnt >/dev/null 2>&1 || true; fi; "
                + "if mountpoint -q /mnt; then "
                + "printf '\\nDroidVM rescue shell; target root: /mnt\\n'; "
                + "else printf '\\nDroidVM rescue shell\\n'; fi; "
                + "PS1='droidvm-rescue # '; stty echo; "
                + "printf '\\n__DROIDVM_%s:SHELL:READY\\n' AGENT__",
            true);
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
                    writeOperationConsole("stty -echo; sync; "
                        + "umount /mnt/proc >/dev/null 2>&1 || true; "
                        + "umount /mnt/dev >/dev/null 2>&1 || true; "
                        + "umount /mnt >/dev/null 2>&1 || "
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
        clearPasswords();
        if (!activityDone) {
            closing = true;
            runOnPool(() -> {
                cleanupVM();
                stopConsoleSession();
            });
        }
    }
}
